"""Storage adapters for QuantBridge local accounts and watchlists."""

from __future__ import annotations

import sqlite3
from pathlib import Path
from typing import Any


class DuplicateUserError(Exception):
    """Raised when creating a user with an email that already exists."""


def _row_dict(row: sqlite3.Row | None) -> dict | None:
    return dict(row) if row is not None else None


class ClosingSQLiteConnection(sqlite3.Connection):
    def __exit__(self, exc_type, exc, tb):
        try:
            return super().__exit__(exc_type, exc, tb)
        finally:
            self.close()


class SQLiteAuthStore:
    def __init__(self, path: Path):
        self.path = Path(path)

    def connect(self) -> sqlite3.Connection:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        conn = sqlite3.connect(self.path, factory=ClosingSQLiteConnection)
        conn.row_factory = sqlite3.Row
        return conn

    def init_schema(self) -> None:
        with self.connect() as conn:
            conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS users (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    email TEXT NOT NULL UNIQUE,
                    display_name TEXT NOT NULL,
                    password_hash TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    deleted_at TEXT
                );

                CREATE TABLE IF NOT EXISTS auth_sessions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    token_hash TEXT NOT NULL UNIQUE,
                    created_at TEXT NOT NULL,
                    expires_at TEXT NOT NULL,
                    revoked_at TEXT
                );

                CREATE TABLE IF NOT EXISTS user_watchlist (
                    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    ticker TEXT NOT NULL,
                    name TEXT NOT NULL,
                    market TEXT NOT NULL,
                    currency TEXT NOT NULL,
                    note TEXT NOT NULL,
                    added_at TEXT NOT NULL,
                    PRIMARY KEY (user_id, ticker)
                );

                CREATE INDEX IF NOT EXISTS idx_auth_sessions_token_active
                    ON auth_sessions (token_hash, revoked_at, expires_at);

                CREATE INDEX IF NOT EXISTS idx_auth_sessions_user
                    ON auth_sessions (user_id, created_at DESC);

                CREATE INDEX IF NOT EXISTS idx_user_watchlist_user_added
                    ON user_watchlist (user_id, added_at DESC);
                """
            )

    def ping(self) -> None:
        with self.connect() as conn:
            conn.execute("SELECT 1").fetchone()

    def create_user(self, email: str, display_name: str, password_hash: str, created_at: str) -> dict:
        try:
            with self.connect() as conn:
                cursor = conn.execute(
                    """
                    INSERT INTO users (email, display_name, password_hash, created_at)
                    VALUES (?, ?, ?, ?)
                    """,
                    (email, display_name, password_hash, created_at),
                )
                user = conn.execute("SELECT * FROM users WHERE id = ?", (cursor.lastrowid,)).fetchone()
        except sqlite3.IntegrityError as exc:
            raise DuplicateUserError(email) from exc
        return dict(user)

    def create_session(self, user_id: int, token_hash: str, created_at: str, expires_at: str) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                INSERT INTO auth_sessions (user_id, token_hash, created_at, expires_at)
                VALUES (?, ?, ?, ?)
                """,
                (user_id, token_hash, created_at, expires_at),
            )

    def user_for_login(self, email: str) -> dict | None:
        with self.connect() as conn:
            return _row_dict(
                conn.execute(
                    "SELECT * FROM users WHERE email = ? AND deleted_at IS NULL",
                    (email,),
                ).fetchone()
            )

    def user_for_session(self, token_hash: str, current_time: str) -> dict | None:
        with self.connect() as conn:
            return _row_dict(
                conn.execute(
                    """
                    SELECT users.*
                    FROM auth_sessions
                    JOIN users ON users.id = auth_sessions.user_id
                    WHERE auth_sessions.token_hash = ?
                      AND auth_sessions.revoked_at IS NULL
                      AND auth_sessions.expires_at > ?
                      AND users.deleted_at IS NULL
                    """,
                    (token_hash, current_time),
                ).fetchone()
            )

    def revoke_session(self, token_hash: str, revoked_at: str) -> None:
        with self.connect() as conn:
            conn.execute(
                "UPDATE auth_sessions SET revoked_at = ? WHERE token_hash = ?",
                (revoked_at, token_hash),
            )

    def delete_user(self, user_id: int, deleted_at: str, token_hash: str | None = None) -> None:
        with self.connect() as conn:
            conn.execute("UPDATE users SET deleted_at = ? WHERE id = ?", (deleted_at, user_id))
            conn.execute("DELETE FROM user_watchlist WHERE user_id = ?", (user_id,))
            if token_hash:
                conn.execute(
                    "UPDATE auth_sessions SET revoked_at = ? WHERE token_hash = ?",
                    (deleted_at, token_hash),
                )

    def list_watchlist(self, user_id: int) -> list[dict]:
        with self.connect() as conn:
            rows = conn.execute(
                """
                SELECT ticker, name, market, currency, note, added_at
                FROM user_watchlist
                WHERE user_id = ?
                ORDER BY added_at DESC
                """,
                (user_id,),
            ).fetchall()
        return [dict(row) for row in rows]

    def upsert_watchlist_item(self, user_id: int, item: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                INSERT INTO user_watchlist (user_id, ticker, name, market, currency, note, added_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(user_id, ticker) DO UPDATE SET
                    name = excluded.name,
                    market = excluded.market,
                    currency = excluded.currency,
                    note = excluded.note
                """,
                (
                    user_id,
                    item["ticker"],
                    item["name"],
                    item["market"],
                    item["currency"],
                    item["note"],
                    item["added_at"],
                ),
            )

    def delete_watchlist_item(self, user_id: int, ticker: str) -> None:
        with self.connect() as conn:
            conn.execute(
                "DELETE FROM user_watchlist WHERE user_id = ? AND ticker = ?",
                (user_id, ticker),
            )


class PostgresAuthStore:
    def __init__(self, database_url: str):
        self.database_url = database_url

    def connect(self):
        import psycopg
        from psycopg.rows import dict_row

        return psycopg.connect(self.database_url, row_factory=dict_row)

    def init_schema(self) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS users (
                    id BIGSERIAL PRIMARY KEY,
                    email TEXT NOT NULL UNIQUE,
                    display_name TEXT NOT NULL,
                    password_hash TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    deleted_at TEXT
                )
                """
            )
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS auth_sessions (
                    id BIGSERIAL PRIMARY KEY,
                    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    token_hash TEXT NOT NULL UNIQUE,
                    created_at TEXT NOT NULL,
                    expires_at TEXT NOT NULL,
                    revoked_at TEXT
                )
                """
            )
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS user_watchlist (
                    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    ticker TEXT NOT NULL,
                    name TEXT NOT NULL,
                    market TEXT NOT NULL,
                    currency TEXT NOT NULL,
                    note TEXT NOT NULL,
                    added_at TEXT NOT NULL,
                    PRIMARY KEY (user_id, ticker)
                )
                """
            )
            conn.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_auth_sessions_token_active
                    ON auth_sessions (token_hash, revoked_at, expires_at)
                """
            )
            conn.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_auth_sessions_user
                    ON auth_sessions (user_id, created_at DESC)
                """
            )
            conn.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_user_watchlist_user_added
                    ON user_watchlist (user_id, added_at DESC)
                """
            )

    def ping(self) -> None:
        with self.connect() as conn:
            conn.execute("SELECT 1").fetchone()

    def create_user(self, email: str, display_name: str, password_hash: str, created_at: str) -> dict:
        try:
            with self.connect() as conn:
                row = conn.execute(
                    """
                    INSERT INTO users (email, display_name, password_hash, created_at)
                    VALUES (%s, %s, %s, %s)
                    RETURNING *
                    """,
                    (email, display_name, password_hash, created_at),
                ).fetchone()
        except Exception as exc:
            if exc.__class__.__name__ == "UniqueViolation":
                raise DuplicateUserError(email) from exc
            raise
        return dict(row)

    def create_session(self, user_id: int, token_hash: str, created_at: str, expires_at: str) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                INSERT INTO auth_sessions (user_id, token_hash, created_at, expires_at)
                VALUES (%s, %s, %s, %s)
                """,
                (user_id, token_hash, created_at, expires_at),
            )

    def user_for_login(self, email: str) -> dict | None:
        with self.connect() as conn:
            row = conn.execute(
                "SELECT * FROM users WHERE email = %s AND deleted_at IS NULL",
                (email,),
            ).fetchone()
        return dict(row) if row is not None else None

    def user_for_session(self, token_hash: str, current_time: str) -> dict | None:
        with self.connect() as conn:
            row = conn.execute(
                """
                SELECT users.*
                FROM auth_sessions
                JOIN users ON users.id = auth_sessions.user_id
                WHERE auth_sessions.token_hash = %s
                  AND auth_sessions.revoked_at IS NULL
                  AND auth_sessions.expires_at > %s
                  AND users.deleted_at IS NULL
                """,
                (token_hash, current_time),
            ).fetchone()
        return dict(row) if row is not None else None

    def revoke_session(self, token_hash: str, revoked_at: str) -> None:
        with self.connect() as conn:
            conn.execute(
                "UPDATE auth_sessions SET revoked_at = %s WHERE token_hash = %s",
                (revoked_at, token_hash),
            )

    def delete_user(self, user_id: int, deleted_at: str, token_hash: str | None = None) -> None:
        with self.connect() as conn:
            conn.execute("UPDATE users SET deleted_at = %s WHERE id = %s", (deleted_at, user_id))
            conn.execute("DELETE FROM user_watchlist WHERE user_id = %s", (user_id,))
            if token_hash:
                conn.execute(
                    "UPDATE auth_sessions SET revoked_at = %s WHERE token_hash = %s",
                    (deleted_at, token_hash),
                )

    def list_watchlist(self, user_id: int) -> list[dict]:
        with self.connect() as conn:
            rows = conn.execute(
                """
                SELECT ticker, name, market, currency, note, added_at
                FROM user_watchlist
                WHERE user_id = %s
                ORDER BY added_at DESC
                """,
                (user_id,),
            ).fetchall()
        return [dict(row) for row in rows]

    def upsert_watchlist_item(self, user_id: int, item: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                INSERT INTO user_watchlist (user_id, ticker, name, market, currency, note, added_at)
                VALUES (%s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT(user_id, ticker) DO UPDATE SET
                    name = excluded.name,
                    market = excluded.market,
                    currency = excluded.currency,
                    note = excluded.note
                """,
                (
                    user_id,
                    item["ticker"],
                    item["name"],
                    item["market"],
                    item["currency"],
                    item["note"],
                    item["added_at"],
                ),
            )

    def delete_watchlist_item(self, user_id: int, ticker: str) -> None:
        with self.connect() as conn:
            conn.execute(
                "DELETE FROM user_watchlist WHERE user_id = %s AND ticker = %s",
                (user_id, ticker),
            )
