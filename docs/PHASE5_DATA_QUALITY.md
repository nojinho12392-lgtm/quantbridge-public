# Phase 5 Data Quality

Phase 5 adds an explicit quality gate for app-facing datasets. It checks the
tables the API, dashboard, Android app, and iOS app depend on before those
surfaces are trusted.

## Commands

```bash
make data-quality
python tools/check_data_quality.py --url https://YOUR-API --warn-only
python tools/check_data_quality.py --max-age-days 14
curl https://YOUR-API/ops/data-quality
```

`--max-age-days 0` is the default and uses the per-dataset freshness threshold.
Pass a positive value only when you intentionally want one threshold for every
dataset.

## Checked Datasets

- `US_Universe`
- `KR_Universe`
- `US_Scored_Stocks`
- `KR_Scored_Stocks`
- `US_Final_Portfolio`
- `KR_Final_Portfolio`
- `US_SmallCap_Gems`
- `KR_SmallCap_Gems`

## Checks

- Required schema columns are present.
- Row counts are above minimum operating thresholds.
- Tickers are present and unique.
- Market labels match the expected market.
- `Last_Updated` is parseable and recent enough.
- Numeric fields such as rank, market cap, score, confidence, bonus, and weight
  stay inside expected ranges.
- Portfolio weights use the app contract's decimal convention and sum to a
  reasonable invested fraction, allowing cash held by dynamic sizing.
- `Data_Confidence` on small-cap sheets is treated as an optional compatibility
  column so older staging snapshots warn instead of failing the whole release.

The API exposes the same report at `GET /ops/data-quality`, and
`GET /ops/health` now includes the data-quality summary as one operating check.
