"""Authentication and user watchlist routes for the QuantBridge API."""

from __future__ import annotations

import hashlib
import hmac
import secrets
import time
from datetime import datetime, timedelta

from fastapi import APIRouter, Depends, Header, HTTPException, Request, status
from pydantic import BaseModel, Field

from api.contracts.mobile_v1 import AuthResponse, CurrentUserResponse, EmptyResponse, WatchlistResponse
from quantbridge.config import get_settings
from api.auth_store import DuplicateUserError, PostgresAuthStore, SQLiteAuthStore


_SETTINGS = get_settings()
_TOKEN_DAYS = 30
_PBKDF2_ITER = 210_000

router = APIRouter()
_rate_limits: dict[str, list[float]] = {}
_STORE = (
    PostgresAuthStore(_SETTINGS.database_url)
    if _SETTINGS.enable_postgres
    else SQLiteAuthStore(_SETTINGS.api_sqlite_path)
)


class SignupRequest(BaseModel):
    email: str = Field(min_length=3, max_length=254)
    password: str = Field(min_length=8, max_length=128)
    display_name: str | None = Field(default=None, max_length=80)


class LoginRequest(BaseModel):
    email: str = Field(min_length=3, max_length=254)
    password: str = Field(min_length=8, max_length=128)


class WatchlistRequest(BaseModel):
    ticker: str = Field(min_length=1, max_length=32)
    name: str = Field(min_length=1, max_length=160)
    market: str = Field(min_length=1, max_length=12)
    currency: str = Field(min_length=1, max_length=12)
    note: str = Field(default="", max_length=80)


def store() -> SQLiteAuthStore:
    return _STORE


def db():
    return store().connect()


def init_db():
    store().init_schema()


def now() -> str:
    return datetime.utcnow().replace(microsecond=0).isoformat()


def _client_ip(request: Request) -> str:
    forwarded = request.headers.get("x-forwarded-for", "").split(",", 1)[0].strip()
    if forwarded:
        return forwarded
    return request.client.host if request.client else "unknown"


def _check_auth_rate_limit(request: Request, scope: str, identity: str) -> None:
    limit = max(_SETTINGS.auth_rate_limit_per_minute, 1)
    current = time.time()
    window_start = current - 60
    key = f"{scope}:{_client_ip(request)}:{identity.strip().lower()[:254]}"
    hits = [ts for ts in _rate_limits.get(key, []) if ts >= window_start]
    if len(hits) >= limit:
        raise HTTPException(
            status.HTTP_429_TOO_MANY_REQUESTS,
            "요청이 너무 많습니다. 잠시 후 다시 시도하세요",
        )
    hits.append(current)
    _rate_limits[key] = hits

    if len(_rate_limits) > 5000:
        stale = [bucket for bucket, values in _rate_limits.items() if not values or values[-1] < window_start]
        for bucket in stale[:1000]:
            _rate_limits.pop(bucket, None)


def _normalize_email(email: str) -> str:
    normalized = email.strip().lower()
    if "@" not in normalized or normalized.startswith("@") or normalized.endswith("@"):
        raise HTTPException(400, "올바른 이메일을 입력하세요")
    return normalized


def _hash_password(password: str) -> str:
    salt = secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, _PBKDF2_ITER)
    return f"pbkdf2_sha256${_PBKDF2_ITER}${salt.hex()}${digest.hex()}"


def _verify_password(password: str, stored: str) -> bool:
    try:
        algorithm, iter_raw, salt_hex, digest_hex = stored.split("$", 3)
        if algorithm != "pbkdf2_sha256":
            return False
        digest = hashlib.pbkdf2_hmac(
            "sha256",
            password.encode(),
            bytes.fromhex(salt_hex),
            int(iter_raw),
        )
        return hmac.compare_digest(digest.hex(), digest_hex)
    except (ValueError, TypeError):
        return False


def _hash_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def _user_payload(row: dict) -> dict:
    return {
        "id": str(row["id"]),
        "email": row["email"],
        "display_name": row["display_name"],
        "created_at": row["created_at"],
    }


def _create_session(user_id: int) -> str:
    token = secrets.token_urlsafe(32)
    created_at = now()
    expires_at = (datetime.utcnow() + timedelta(days=_TOKEN_DAYS)).replace(microsecond=0).isoformat()
    store().create_session(user_id, _hash_token(token), created_at, expires_at)
    return token


def require_user(authorization: str | None = Header(default=None)) -> dict:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "로그인이 필요합니다")

    token_hash = _hash_token(authorization.removeprefix("Bearer ").strip())
    user = store().user_for_session(token_hash, now())
    if user is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "세션이 만료되었습니다")
    return user


@router.post("/auth/signup", response_model=AuthResponse)
def auth_signup(payload: SignupRequest, request: Request):
    _check_auth_rate_limit(request, "signup", payload.email)
    email = _normalize_email(payload.email)
    display_name = (payload.display_name or email.split("@")[0]).strip()
    if not display_name:
        display_name = email.split("@")[0]

    try:
        user = store().create_user(email, display_name, _hash_password(payload.password), now())
        token = _create_session(int(user["id"]))
    except DuplicateUserError:
        raise HTTPException(409, "이미 가입된 이메일입니다")

    return {"access_token": token, "token_type": "bearer", "user": _user_payload(user)}


@router.post("/auth/login", response_model=AuthResponse)
def auth_login(payload: LoginRequest, request: Request):
    _check_auth_rate_limit(request, "login", payload.email)
    email = _normalize_email(payload.email)
    user = store().user_for_login(email)
    if user is None or not _verify_password(payload.password, user["password_hash"]):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다")
    token = _create_session(int(user["id"]))

    return {"access_token": token, "token_type": "bearer", "user": _user_payload(user)}


@router.get("/auth/me", response_model=CurrentUserResponse)
def auth_me(user: dict = Depends(require_user)):
    return {"user": _user_payload(user)}


@router.post("/auth/logout", response_model=EmptyResponse)
def auth_logout(authorization: str | None = Header(default=None)):
    if authorization and authorization.startswith("Bearer "):
        store().revoke_session(_hash_token(authorization.removeprefix("Bearer ").strip()), now())
    return {"ok": True}


@router.delete("/auth/me", response_model=EmptyResponse)
def auth_delete_me(
    user: dict = Depends(require_user),
    authorization: str | None = Header(default=None),
):
    token_hash = None
    if authorization and authorization.startswith("Bearer "):
        token_hash = _hash_token(authorization.removeprefix("Bearer ").strip())
    store().delete_user(int(user["id"]), now(), token_hash=token_hash)
    return {"ok": True}


@router.get("/me/watchlist", response_model=WatchlistResponse)
def my_watchlist(user: dict = Depends(require_user)):
    return {"items": store().list_watchlist(int(user["id"]))}


@router.post("/me/watchlist", response_model=EmptyResponse)
def save_watchlist_item(payload: WatchlistRequest, user: dict = Depends(require_user)):
    ticker = payload.ticker.strip().upper()
    store().upsert_watchlist_item(
        int(user["id"]),
        {
            "ticker": ticker,
            "name": payload.name.strip(),
            "market": payload.market.strip().upper(),
            "currency": payload.currency.strip().upper(),
            "note": payload.note.strip(),
            "added_at": now(),
        },
    )
    return {"ok": True}


@router.delete("/me/watchlist/{ticker}", response_model=EmptyResponse)
def delete_watchlist_item(ticker: str, user: dict = Depends(require_user)):
    store().delete_watchlist_item(int(user["id"]), ticker.strip().upper())
    return {"ok": True}
