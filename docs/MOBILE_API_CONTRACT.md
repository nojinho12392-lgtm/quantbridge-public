# 큐빗 Mobile API Contract v1

이 문서는 iOS, Android, FastAPI 서버가 같은 필드 의미를 보도록 고정하는 최소 계약이다.
정식 스키마 소스는 `api/contracts/mobile_v1.py`이고, 앱 화면은 이 계약을 기준으로 파싱한다.

## 공통 규칙

- 시장 값은 `US`, `KR`, 통합 조회는 `ALL`을 사용한다.
- 가격, 수익률, 시가총액은 숫자 또는 `null`이다. 문자열 포맷팅은 모바일에서 담당한다.
- 자동갱신 화면은 `updated_at`, `generated_at`, `Price_Updated_At`, `Daily_Change_Horizon` 중 가능한 값을 표시한다.
- 당일 등락률이 없으면 앱은 `마지막 성공 데이터` 또는 `전장` 라벨을 함께 보여준다.
- 티커 요청은 클라이언트가 중복을 보내도 서버가 중복 제거와 캐시 키 정규화를 담당한다.

## 핵심 엔드포인트

### `/portfolio/{market}`

기업 분석 순위 화면의 기본 목록이다.

필수 응답:
- `meta`
- `stocks`
- `stocks[].Ticker`
- `stocks[].Name`
- `stocks[].Rank`
- `stocks[].Current_Price`
- `stocks[].Return_1M`
- `stocks[].MarketCap`

### `/portfolio/{market}/prices`

홈, 관심, 섹터 상세에서 가벼운 가격 갱신에 사용한다.

필수 응답:
- `market`
- `metrics`
- `source`
- `updated_at`
- `metrics[].Ticker`
- `metrics[].Current_Price`
- `metrics[].Return_1M`
- `metrics[].Daily_Change_Pct`
- `metrics[].Daily_Change_Horizon`
- `metrics[].Price_Updated_At`

### `/sectors/themes`

분석 > 섹터의 테마 카드와 상세 구성기업에 사용한다.

필수 응답:
- `market`
- `generated_at`
- `count`
- `items`
- `items[].label`
- `items[].avg_change_pct`
- `items[].member_count`
- `items[].priced_count`
- `items[].missing_price_count`
- `items[].price_coverage_ratio`
- `items[].weighting_method`
- `items[].leader`
- `items[].members`
- `items[].members[].Ticker`
- `items[].members[].Name`
- `items[].members[].Current_Price`
- `items[].members[].Daily_Change_Pct`
- `items[].members[].Daily_Change_Horizon`
- `items[].members[].MarketCap`

섹터 등락률은 구성기업 단순 평균이 아니라 가능한 경우 시가총액 가중 평균이다.

### `/search/universe`

분석 > 기업 검색과 최근 검색 재조회에 사용한다. 인기 검색은 제공하지 않고, 클라이언트는 사용자가 직접 입력한 최근 검색만 로컬에 저장한다.

필수 응답:
- `stocks`
- `count`
- `query`
- `market`
- `groups`
- `stocks[].Ticker`
- `stocks[].Name`
- `stocks[].Market`
- `stocks[].Sector`
- `stocks[].MarketCap`
- `stocks[].In_Portfolio`
- `stocks[].In_SmallCap`
- `stocks[].Currency`
- `groups[].label`
- `groups[].count`
- `groups[].tickers`

### `/etfs`

분석 > ETF 목록과 검색 결과에 사용한다.

필수 응답:
- `items`
- `count`
- `source`
- `items[].ticker`
- `items[].name`
- `items[].market`
- `items[].category`
- `items[].price`
- `items[].changePct`
- `items[].updatedAt`
- `items[].topHoldings`

검색어 `q`가 들어오면 서버 저장 목록에 없던 ETF도 보조 검색 결과로 합쳐서 돌려줄 수 있다.

### `/etfs/{ticker}`

ETF 상세 설명, 가격, 보유종목, 노출 비중, 요약 설명에 사용한다.

필수 응답:
- `item`
- `holdings`
- `exposures`
- `summary`

### `/news/issues`

인사이트 > 뉴스의 시장 영향 피드에 사용한다.

필수 응답:
- `configured`
- `items`
- `items[].title`
- `items[].summary`
- `items[].source`
- `items[].url`
- `items[].market`
- `items[].relatedTickers`
- `items[].impactScore`

### `/market/indicators`

홈 상단 지수, 관심 지수, 주요 지표 화면에 사용한다.

필수 응답:
- `items`
- `count`
- `updated_at`
- `source`
- `items[].symbol`
- `items[].label`
- `items[].value`
- `items[].change_abs`
- `items[].change_pct`
- `items[].updated_at`

### `/market/indicators/history`

관심 지수/원자재/환율 그래프에 사용한다.

필수 응답:
- `series`
- `updated_at`

### `/research/policy-adjusted-ranking`

분석 > 품질의 정책 조정 섀도 랭킹에 사용한다. 이 엔드포인트는 실제 추천 순위표를 덮어쓰지 않고, 리서치 품질 배치가 만든 관찰용 랭킹만 내려준다.

요청:
- `market`: `US` 또는 `KR`
- `limit`: 1-200

필수 응답:
- `market`
- `items`
- `count`
- `summary`
- `top_up`
- `top_down`
- `source`
- `mode`
- `items[].Ticker`
- `items[].Name`
- `items[].Policy_Rank`
- `items[].Base_Rank`
- `items[].Rank_Change`
- `items[].Policy_Final_Score`
- `items[].Base_Final_Score`
- `items[].Policy_Actions`
- `items[].Policy_Mode`
- `summary.Rows`
- `summary.Top_Up_Ticker`
- `summary.Top_Down_Ticker`

### `/me/watchlist`

계정 동기화용 관심목록이다.

규칙:
- 세션 만료 또는 로그아웃은 로컬 관심목록을 지우지 않는다.
- 계정 삭제만 서버 관심목록과 로컬 관심목록 삭제를 동시에 수행한다.
- 동기화 실패 시 앱은 pending operation을 보존하고 다음 로그인/재시도 때 다시 전송한다.
