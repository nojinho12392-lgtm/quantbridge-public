import unittest
from datetime import datetime, timezone

from api.services.system_api import SystemPayloadBuilder


class _DbConnection:
    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        return False

    def execute(self, query):
        self.query = query
        return self

    def fetchone(self):
        return (1,)


class SystemPayloadBuilderTests(unittest.TestCase):
    def test_ready_payload_marks_disabled_postgres_ready_after_auth_check(self):
        builder = SystemPayloadBuilder(
            utc_now=lambda: datetime(2026, 5, 27, tzinfo=timezone.utc),
            auth_db=lambda: _DbConnection(),
            repository=lambda: None,
            enable_postgres=lambda: False,
        )

        payload = builder.ready_payload()

        self.assertEqual(payload["status"], "ready")
        self.assertEqual(payload["auth_store"], "ok")
        self.assertEqual(payload["sqlite"], "ok")
        self.assertEqual(payload["postgres"], "disabled")
        self.assertEqual(payload["ts"], "2026-05-27T00:00:00+00:00")


if __name__ == "__main__":
    unittest.main()
