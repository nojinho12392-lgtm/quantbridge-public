"""QuantBridge shared package.

This package is the new stable layer for configuration, schemas, storage, and
orchestration. Legacy pipeline scripts can keep running while they are migrated
module by module.
"""

from .config import get_settings

__all__ = ["get_settings"]
