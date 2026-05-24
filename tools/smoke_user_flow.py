#!/usr/bin/env python3
"""Smoke-test app user flows: auth, watchlist sync, and stock detail."""

from __future__ import annotations

import argparse
import subprocess
import time
import uuid

import requests


def detect_lan_ip() -> str:
    for iface in ("en0", "en1"):
        try:
            out = subprocess.check_output(["ipconfig", "getifaddr", iface], text=True).strip()
            if out:
                return out
        except Exception:
            pass
    return "127.0.0.1"


def request_json(
    session: requests.Session,
    method: str,
    base_url: str,
    path: str,
    token: str | None = None,
    timeout: float = 30.0,
    **kwargs,
) -> tuple[float, dict]:
    headers = dict(kwargs.pop("headers", {}) or {})
    if token:
        headers["Authorization"] = f"Bearer {token}"
    start = time.perf_counter()
    response = session.request(method, f"{base_url.rstrip('/')}{path}", headers=headers, timeout=timeout, **kwargs)
    elapsed = time.perf_counter() - start
    response.raise_for_status()
    return elapsed, response.json()


def main() -> int:
    parser = argparse.ArgumentParser(description="Smoke-test app user flows against QuantBridge API")
    parser.add_argument("--url", default="", help="API base URL. Defaults to current LAN IP.")
    parser.add_argument("--keep-user", action="store_true", help="Do not delete the smoke-test account")
    parser.add_argument("--skip-detail", action="store_true", help="Skip stock detail check and test only account/watchlist flow")
    parser.add_argument("--timeout", type=float, default=30.0, help="Per-request timeout in seconds")
    args = parser.parse_args()

    base_url = args.url.strip().rstrip("/") or f"http://{detect_lan_ip()}:8000"
    email = f"smoke-{uuid.uuid4().hex[:10]}@quantbridge.local"
    password = f"Smoke-{uuid.uuid4().hex[:12]}"
    session = requests.Session()

    print(f"[user-flow] base_url={base_url}")

    elapsed, ready = request_json(session, "GET", base_url, "/ready", timeout=args.timeout)
    print(f"  ready        {elapsed:>6.3f}s  status={ready.get('status')} cache={ready.get('cache')}")

    elapsed, signup = request_json(
        session,
        "POST",
        base_url,
        "/auth/signup",
        timeout=args.timeout,
        json={"email": email, "password": password, "display_name": "Smoke User"},
    )
    token = signup["access_token"]
    print(f"  signup       {elapsed:>6.3f}s  user={signup.get('user', {}).get('email')}")

    elapsed, me = request_json(session, "GET", base_url, "/auth/me", token=token, timeout=args.timeout)
    print(f"  me           {elapsed:>6.3f}s  user={me.get('user', {}).get('display_name')}")

    elapsed, login = request_json(
        session,
        "POST",
        base_url,
        "/auth/login",
        timeout=args.timeout,
        json={"email": email, "password": password},
    )
    login_token = login["access_token"]
    print(f"  login        {elapsed:>6.3f}s  user={login.get('user', {}).get('email')}")

    elapsed, logout = request_json(session, "POST", base_url, "/auth/logout", token=login_token, timeout=args.timeout)
    print(f"  logout       {elapsed:>6.3f}s  ok={logout.get('ok')}")
    revoked = session.get(
        f"{base_url.rstrip('/')}/auth/me",
        headers={"Authorization": f"Bearer {login_token}"},
        timeout=args.timeout,
    )
    if revoked.status_code != 401:
        raise RuntimeError(f"logged-out token still accepted: {revoked.status_code}")
    print("  logout_me        OK  token rejected")

    watch_item = {
        "ticker": "005930.KS",
        "name": "삼성전자",
        "market": "KR",
        "currency": "KRW",
        "note": "smoke",
    }
    elapsed, saved = request_json(session, "POST", base_url, "/me/watchlist", token=token, timeout=args.timeout, json=watch_item)
    print(f"  watch_save   {elapsed:>6.3f}s  ok={saved.get('ok')}")

    elapsed, watchlist = request_json(session, "GET", base_url, "/me/watchlist", token=token, timeout=args.timeout)
    items = watchlist.get("items", [])
    if not any(item.get("ticker") == "005930.KS" for item in items):
        raise RuntimeError("watchlist item was not returned")
    print(f"  watch_list   {elapsed:>6.3f}s  items={len(items)}")

    if not args.skip_detail:
        elapsed, detail = request_json(
            session,
            "GET",
            base_url,
            "/stock/005930.KS?period=5y&profile=true&detail_schema=valuation_v1",
            timeout=args.timeout,
        )
        info = detail.get("info", {})
        if not detail.get("prices"):
            raise RuntimeError("stock detail returned no prices")
        if not info.get("name"):
            raise RuntimeError("stock detail returned no company name")
        valuation = info.get("pe_ratio") or info.get("forward_pe") or info.get("price_to_book")
        if valuation is None:
            raise RuntimeError("stock detail returned no valuation metric")
        print(
            f"  detail       {elapsed:>6.3f}s  source={detail.get('source')} "
            f"prices={len(detail.get('prices', []))} name={info.get('name')} valuation={valuation}"
        )

    elapsed, deleted = request_json(session, "DELETE", base_url, "/me/watchlist/005930.KS", token=token, timeout=args.timeout)
    print(f"  watch_delete {elapsed:>6.3f}s  ok={deleted.get('ok')}")

    elapsed, watchlist_after_delete = request_json(session, "GET", base_url, "/me/watchlist", token=token, timeout=args.timeout)
    if any(item.get("ticker") == "005930.KS" for item in watchlist_after_delete.get("items", [])):
        raise RuntimeError("watchlist item still exists after delete")
    print(f"  watch_empty  {elapsed:>6.3f}s  items={len(watchlist_after_delete.get('items', []))}")

    if args.keep_user:
        elapsed, final_logout = request_json(session, "POST", base_url, "/auth/logout", token=token, timeout=args.timeout)
        print(f"  final_logout {elapsed:>6.3f}s  ok={final_logout.get('ok')}")
    else:
        elapsed, deleted_user = request_json(session, "DELETE", base_url, "/auth/me", token=token, timeout=args.timeout)
        print(f"  delete_user  {elapsed:>6.3f}s  ok={deleted_user.get('ok')}")
        post_delete = session.get(
            f"{base_url.rstrip('/')}/auth/me",
            headers={"Authorization": f"Bearer {token}"},
            timeout=args.timeout,
        )
        if post_delete.status_code != 401:
            raise RuntimeError(f"deleted account token still accepted: {post_delete.status_code}")
        print("  deleted_me       OK  token rejected")

    print("[user-flow] ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
