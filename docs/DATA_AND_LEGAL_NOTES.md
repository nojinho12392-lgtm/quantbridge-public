# Data And Legal Notes

This project separates code from data. The repository should not include
downloaded market data, third-party API responses, or licensed datasets.

## Safer Data Patterns

- Use public filings and disclosures where terms permit reuse.
- Let users bring their own API keys and data files.
- Keep external data calls optional and disabled by default for demos.
- Show source, timestamp, delay, and limitation information in production apps.

## Riskier Data Patterns

- Redistributing real-time or delayed exchange prices without a license.
- Publishing cached KRX/Koscom, Yahoo Finance, Naver Finance, or brokerage data.
- Using scraped data in a commercial app without confirming provider terms.
- Shipping production API keys inside mobile apps or public repositories.

## Product Wording

The app should help users document and review decisions. It should not present
itself as a personalized investment-advice or trade-signal service.

Prefer:

- "Check your investment thesis"
- "Signals that conflict with your criteria"
- "Risk items to review"
- "Decision note"

Avoid:

- "Buy this stock"
- "Sell this stock"
- "Put 20% of your portfolio here"
- "This stock will outperform"
