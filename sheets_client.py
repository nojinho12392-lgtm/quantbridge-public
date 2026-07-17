"""
sheets_client.py — 안정적인 Google Sheets 연결 헬퍼

문제: google-auth의 기본 connection pool이 죽은 TCP 연결을 재사용하려다
     RemoteDisconnected / 무한 대기 발생 (macOS 환경에서 빈번)

해결: Connection: close 헤더 강제 → 매 요청마다 새 TCP 연결 사용
     timeout=30s 설정 → 영원히 대기하지 않음

사용법 (모든 파이프라인 파일에서):
    from sheets_client import get_spreadsheet
    ss = get_spreadsheet()                         # Jino_Quant_Database
    ss = get_spreadsheet("다른_시트_이름")
"""

import json

import requests as _requests
import gspread
from google.oauth2.service_account import Credentials
from google.auth.transport.requests import AuthorizedSession, Request as _AuthRequest
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry
from quantbridge.config import get_settings

_SETTINGS = get_settings()
_KEY_JSON = str(_SETTINGS.google_key_path)
_SCOPES   = [
    "https://spreadsheets.google.com/feeds",
    "https://www.googleapis.com/auth/drive",
]

_SPREADSHEET_ID = _SETTINGS.spreadsheet_id


class _NoKeepAliveAdapter(HTTPAdapter):
    """매 요청마다 새 TCP 연결 사용 — 죽은 연결 재사용 방지"""
    def send(self, request, **kwargs):
        request.headers["Connection"] = "close"
        kwargs.setdefault("timeout", (30, 120))  # (connect, read) timeout — www.googleapis.com TCP ~10s
        return super().send(request, **kwargs)


def _load_credentials(key_path: str = _KEY_JSON) -> Credentials:
    settings = get_settings()
    if settings.google_key_json.strip():
        info = json.loads(settings.google_key_json)
        return Credentials.from_service_account_info(info, scopes=_SCOPES)
    return Credentials.from_service_account_file(key_path, scopes=_SCOPES)


def get_client(key_path: str = _KEY_JSON) -> gspread.Client:
    creds = _load_credentials(key_path)

    retry = Retry(
        total=3,
        backoff_factor=2,
        status_forcelist=[429, 500, 502, 503, 504],
        allowed_methods=["GET", "POST", "PUT"],
    )
    adapter = _NoKeepAliveAdapter(max_retries=retry)

    # Apply timeout adapter to OAuth token-refresh requests too
    auth_session = _requests.Session()
    auth_session.mount("https://", adapter)
    auth_request = _AuthRequest(session=auth_session)

    # Main API session
    session = AuthorizedSession(creds, auth_request=auth_request)
    session.mount("https://", adapter)

    # gspread 6.x: session must be passed to Client constructor, not set afterwards
    client = gspread.Client(auth=creds, session=session)
    return client


def get_spreadsheet(name: str = "Jino_Quant_Database",
                    key_path: str = _KEY_JSON) -> gspread.Spreadsheet:
    # open_by_key uses Sheets API directly (fast); open() uses Drive search (slow)
    settings = get_settings()
    sheet_id = settings.spreadsheet_id
    return get_client(key_path).open_by_key(sheet_id)
