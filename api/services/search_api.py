from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass

from api.services.company_names import apply_localized_names


@dataclass(frozen=True)
class SearchApiService:
    cached: Callable
    payload: Callable[[str, int], dict]

    def search_universe(self, q: str = "", limit: int = 100) -> dict:
        safe_limit = max(1, min(int(limit or 100), 200))
        clean_query = str(q or "").strip()
        cache_key = f"search_universe_{clean_query.lower()}_{safe_limit}"
        return self.cached(cache_key, lambda: apply_localized_names(self.payload(clean_query, safe_limit)))
