from pathlib import Path

from api.services.etf_insights import ETF_UNIVERSE_CSV, detail_from_records, payload_from_records
from tools.sync_etf_insights_storage import build_rows


def test_etf_universe_csv_expands_mobile_catalog():
    payload = payload_from_records(limit=500)

    assert payload["source"] == "csv_seed"
    assert payload["count"] >= 100
    assert payload["items"][0]["ticker"] == "QQQ"
    assert payload_from_records(market="US", limit=500)["count"] >= 70
    assert payload_from_records(market="KR", limit=500)["count"] >= 30
    assert payload_from_records(category="채권", limit=500)["count"] >= 10


def test_etf_storage_rows_round_trip_through_api_payload():
    rows = build_rows(Path(ETF_UNIVERSE_CSV))
    payload = payload_from_records(rows, limit=500)
    detail = detail_from_records(rows, "371460")

    assert payload["source"] == "storage"
    assert payload["count"] == len(rows)
    assert detail is not None
    assert detail["item"]["name"] == "TIGER 차이나전기차SOLACTIVE"
    assert all(isinstance(item["holdings"], list) for item in payload["items"])
    assert all(isinstance(item["exposures"], list) for item in payload["items"])


def test_etf_short_storage_snapshot_is_merged_with_csv_seed():
    old_storage_subset = build_rows(Path(ETF_UNIVERSE_CSV), limit=18)
    payload = payload_from_records(old_storage_subset, limit=500)

    assert payload["source"] == "storage+csv_seed"
    assert payload["count"] >= 100
    assert any(item["ticker"] == "371460" for item in payload["items"])
