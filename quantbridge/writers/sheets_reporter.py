"""Google Sheets report writer.

Sheets should be treated as a human-facing report sink, not the primary
database. This helper centralizes the remaining report-write behavior.
"""

from __future__ import annotations

import pandas as pd


class SheetsReporter:
    def __init__(self, spreadsheet):
        self.spreadsheet = spreadsheet

    def write_dataframe(self, sheet_name: str, df: pd.DataFrame, clear: bool = True) -> None:
        try:
            ws = self.spreadsheet.worksheet(sheet_name)
        except Exception:
            ws = self.spreadsheet.add_worksheet(
                title=sheet_name,
                rows=max(1000, len(df) + 50),
                cols=max(20, len(df.columns) + 2),
            )
        if clear:
            ws.clear()
        out = df.where(df.notna(), "").astype(str)
        ws.update(range_name="A1", values=[out.columns.tolist()] + out.values.tolist())
