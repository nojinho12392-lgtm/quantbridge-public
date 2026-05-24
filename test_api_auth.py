"""Behavior tests for QuantBridge auth and watchlist API routes."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from fastapi import FastAPI
from fastapi.testclient import TestClient

import api.auth as auth
from api.auth_store import SQLiteAuthStore


def _client(db_path: Path) -> TestClient:
    auth._STORE = SQLiteAuthStore(db_path)
    auth._rate_limits.clear()
    app = FastAPI()
    app.include_router(auth.router)
    auth.init_db()
    return TestClient(app)


class AuthRouteTests(unittest.TestCase):
    def test_signup_login_me_and_watchlist_flow(self):
        with tempfile.TemporaryDirectory() as tmp:
            client = _client(Path(tmp) / "auth.sqlite3")

            signup = client.post(
                "/auth/signup",
                json={
                    "email": "USER@example.com",
                    "password": "strong-password",
                    "display_name": "Jino",
                },
            )
            self.assertEqual(signup.status_code, 200)
            token = signup.json()["access_token"]

            me = client.get("/auth/me", headers={"Authorization": f"Bearer {token}"})
            self.assertEqual(me.status_code, 200)
            self.assertEqual(me.json()["user"]["email"], "user@example.com")

            saved = client.post(
                "/me/watchlist",
                headers={"Authorization": f"Bearer {token}"},
                json={
                    "ticker": "aapl",
                    "name": "Apple",
                    "market": "us",
                    "currency": "usd",
                    "note": "Core holding",
                },
            )
            self.assertEqual(saved.status_code, 200)

            watchlist = client.get("/me/watchlist", headers={"Authorization": f"Bearer {token}"})
            self.assertEqual(watchlist.status_code, 200)
            self.assertEqual(watchlist.json()["items"][0]["ticker"], "AAPL")
            self.assertEqual(watchlist.json()["items"][0]["market"], "US")

            deleted = client.delete("/me/watchlist/AAPL", headers={"Authorization": f"Bearer {token}"})
            self.assertEqual(deleted.status_code, 200)

            empty_watchlist = client.get("/me/watchlist", headers={"Authorization": f"Bearer {token}"})
            self.assertEqual(empty_watchlist.status_code, 200)
            self.assertEqual(empty_watchlist.json()["items"], [])

            login = client.post(
                "/auth/login",
                json={"email": "user@example.com", "password": "strong-password"},
            )
            self.assertEqual(login.status_code, 200)
            login_token = login.json()["access_token"]
            self.assertTrue(login_token)

            logout = client.post("/auth/logout", headers={"Authorization": f"Bearer {login_token}"})
            self.assertEqual(logout.status_code, 200)

            logged_out_me = client.get("/auth/me", headers={"Authorization": f"Bearer {login_token}"})
            self.assertEqual(logged_out_me.status_code, 401)

            original_session_still_valid = client.get("/auth/me", headers={"Authorization": f"Bearer {token}"})
            self.assertEqual(original_session_still_valid.status_code, 200)

            deleted_user = client.delete("/auth/me", headers={"Authorization": f"Bearer {token}"})
            self.assertEqual(deleted_user.status_code, 200)

            me_after_delete = client.get("/auth/me", headers={"Authorization": f"Bearer {token}"})
            self.assertEqual(me_after_delete.status_code, 401)

    def test_invalid_login_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            client = _client(Path(tmp) / "auth.sqlite3")
            client.post(
                "/auth/signup",
                json={"email": "user@example.com", "password": "strong-password"},
            )

            response = client.post(
                "/auth/login",
                json={"email": "user@example.com", "password": "wrong-password"},
            )

            self.assertEqual(response.status_code, 401)


if __name__ == "__main__":
    unittest.main()
