"""Unit tests for QuantBridge runtime settings."""

from __future__ import annotations

import os
import unittest
from pathlib import Path
from unittest.mock import patch

from pydantic import ValidationError

from quantbridge.config import DEFAULT_DATABASE_URL, ROOT, Settings


class SettingsTests(unittest.TestCase):
    def test_local_defaults_preserve_existing_workflow(self):
        with patch.dict(os.environ, {}, clear=True):
            settings = Settings(_env_file=None)

        self.assertEqual(settings.api_env, "local")
        self.assertEqual(settings.cors_origins, ("*",))
        self.assertFalse(settings.allow_cors_credentials)
        self.assertEqual(settings.database_url, DEFAULT_DATABASE_URL)
        self.assertEqual(settings.ml_blend_weight, 0.25)
        self.assertEqual(settings.ml_blend_weak_weight, 0.10)
        self.assertEqual(settings.ml_blend_strong_weight, 0.35)
        self.assertTrue(settings.ml_auto_weight)
        self.assertEqual(settings.ml_factor_score_col, "Final_Score")

    def test_comma_separated_cors_and_relative_paths_are_normalized(self):
        with patch.dict(
            os.environ,
            {
                "QUANT_CORS_ORIGINS": "https://app.example.com, https://admin.example.com",
                "QUANT_API_SQLITE_PATH": "tmp/auth.sqlite3",
                "QUANT_PIPELINE_RUNNER": "LEGACY",
            },
            clear=True,
        ):
            settings = Settings(_env_file=None)

        self.assertEqual(settings.cors_origins, ("https://app.example.com", "https://admin.example.com"))
        self.assertTrue(settings.allow_cors_credentials)
        self.assertEqual(settings.api_sqlite_path, ROOT / Path("tmp/auth.sqlite3"))
        self.assertEqual(settings.pipeline_runner, "legacy")

    def test_production_rejects_wildcard_cors(self):
        with patch.dict(os.environ, {"QUANT_API_ENV": "production", "QUANT_CORS_ORIGINS": "*"}, clear=True):
            with self.assertRaises(ValidationError):
                Settings(_env_file=None)

    def test_ml_blend_weights_must_be_between_zero_and_one(self):
        with patch.dict(os.environ, {"QUANT_ML_BLEND_WEIGHT": "1.5"}, clear=True):
            with self.assertRaises(ValidationError):
                Settings(_env_file=None)


if __name__ == "__main__":
    unittest.main()
