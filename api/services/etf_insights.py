from __future__ import annotations

import csv
import json
from functools import lru_cache
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable


ROOT_DIR = Path(__file__).resolve().parents[2]
ETF_UNIVERSE_CSV = ROOT_DIR / "data" / "etf_universe.csv"


ETF_INSIGHTS: list[dict] = [
    {
        "ticker": "QQQ",
        "name": "Invesco QQQ",
        "region": "US",
        "category": "성장",
        "theme": "나스닥 100 · 빅테크",
        "summary": "대형 기술주와 성장주 노출이 강한 대표 ETF입니다.",
        "expenseRatio": "0.20%",
        "aum": "$280B+",
        "distribution": "분기",
        "outlook": "AI, 클라우드, 반도체 모멘텀이 유지될 때 강합니다.",
        "risk": "상위 빅테크 집중도가 높아 금리 상승과 기술주 조정에 민감합니다.",
        "holdings": [
            {"ticker": "NVDA", "name": "NVIDIA", "weight": 0.082},
            {"ticker": "MSFT", "name": "Microsoft", "weight": 0.078},
            {"ticker": "AAPL", "name": "Apple", "weight": 0.073},
            {"ticker": "AMZN", "name": "Amazon", "weight": 0.052},
            {"ticker": "AVGO", "name": "Broadcom", "weight": 0.047},
        ],
        "exposures": [
            {"label": "기술", "weight": 0.55},
            {"label": "커뮤니케이션", "weight": 0.16},
            {"label": "경기소비재", "weight": 0.13},
            {"label": "헬스케어", "weight": 0.06},
        ],
    },
    {
        "ticker": "VOO",
        "name": "Vanguard S&P 500",
        "region": "US",
        "category": "대표지수",
        "theme": "미국 대형주",
        "summary": "미국 대형주 시장 전체에 가까운 노출을 주는 핵심 지수 ETF입니다.",
        "expenseRatio": "0.03%",
        "aum": "$1T+",
        "distribution": "분기",
        "outlook": "미국 이익 사이클과 위험선호가 살아날 때 안정적인 코어 역할을 합니다.",
        "risk": "시총가중 구조라 대형 기술주 비중이 높아질수록 QQQ와 중복 노출이 커집니다.",
        "holdings": [
            {"ticker": "NVDA", "name": "NVIDIA", "weight": 0.073},
            {"ticker": "MSFT", "name": "Microsoft", "weight": 0.067},
            {"ticker": "AAPL", "name": "Apple", "weight": 0.061},
            {"ticker": "AMZN", "name": "Amazon", "weight": 0.038},
            {"ticker": "META", "name": "Meta", "weight": 0.029},
        ],
        "exposures": [
            {"label": "기술", "weight": 0.34},
            {"label": "금융", "weight": 0.13},
            {"label": "헬스케어", "weight": 0.10},
            {"label": "경기소비재", "weight": 0.10},
        ],
    },
    {
        "ticker": "SCHD",
        "name": "Schwab US Dividend Equity",
        "region": "US",
        "category": "배당",
        "theme": "미국 배당성장",
        "summary": "배당 지속성과 현금흐름이 좋은 미국 대형주를 중심으로 담는 ETF입니다.",
        "expenseRatio": "0.06%",
        "aum": "$60B+",
        "distribution": "분기",
        "outlook": "금리 안정과 배당주 선호가 강해질 때 방어적 코어 역할을 기대할 수 있습니다.",
        "risk": "고성장 기술주 랠리 구간에서는 성장주 ETF보다 상대 성과가 약할 수 있습니다.",
        "holdings": [
            {"ticker": "HD", "name": "Home Depot", "weight": 0.045},
            {"ticker": "ABBV", "name": "AbbVie", "weight": 0.043},
            {"ticker": "AMGN", "name": "Amgen", "weight": 0.041},
            {"ticker": "KO", "name": "Coca-Cola", "weight": 0.039},
            {"ticker": "PEP", "name": "PepsiCo", "weight": 0.036},
        ],
        "exposures": [
            {"label": "산업재", "weight": 0.18},
            {"label": "헬스케어", "weight": 0.17},
            {"label": "필수소비재", "weight": 0.14},
            {"label": "금융", "weight": 0.13},
        ],
    },
    {
        "ticker": "SOXX",
        "name": "iShares Semiconductor",
        "region": "US",
        "category": "섹터",
        "theme": "반도체",
        "summary": "반도체 설계, 장비, 파운드리 밸류체인에 집중 투자하는 섹터 ETF입니다.",
        "expenseRatio": "0.35%",
        "aum": "$10B+",
        "distribution": "분기",
        "outlook": "AI 설비투자와 메모리 업황 회복 기대가 강할 때 탄력이 큽니다.",
        "risk": "업황 사이클, 미국 수출규제, 특정 대형주 비중 변화에 크게 흔들릴 수 있습니다.",
        "holdings": [
            {"ticker": "NVDA", "name": "NVIDIA", "weight": 0.095},
            {"ticker": "AVGO", "name": "Broadcom", "weight": 0.083},
            {"ticker": "AMD", "name": "AMD", "weight": 0.067},
            {"ticker": "QCOM", "name": "Qualcomm", "weight": 0.061},
            {"ticker": "TXN", "name": "Texas Instruments", "weight": 0.047},
        ],
        "exposures": [
            {"label": "반도체", "weight": 0.82},
            {"label": "장비", "weight": 0.12},
            {"label": "현금/기타", "weight": 0.06},
        ],
    },
    {
        "ticker": "379800",
        "name": "KODEX 미국S&P500",
        "region": "KR",
        "category": "해외지수",
        "theme": "국내상장 미국 대형주",
        "summary": "국내 계좌로 미국 S&P 500 노출을 얻는 대표적인 국내상장 해외 ETF입니다.",
        "expenseRatio": "0.05%대",
        "aum": "국내 대형",
        "distribution": "상품별 상이",
        "outlook": "환율과 미국 대형주 흐름을 동시에 확인해야 합니다.",
        "risk": "원달러 환율, 환헤지 여부, 국내장 거래 시간과 미국장 시차를 함께 봐야 합니다.",
        "holdings": [
            {"ticker": "NVDA", "name": "NVIDIA", "weight": 0.073},
            {"ticker": "MSFT", "name": "Microsoft", "weight": 0.067},
            {"ticker": "AAPL", "name": "Apple", "weight": 0.061},
            {"ticker": "AMZN", "name": "Amazon", "weight": 0.038},
            {"ticker": "META", "name": "Meta", "weight": 0.029},
        ],
        "exposures": [
            {"label": "미국", "weight": 0.96},
            {"label": "현금/기타", "weight": 0.04},
        ],
    },
    {
        "ticker": "069500",
        "name": "KODEX 200",
        "region": "KR",
        "category": "대표지수",
        "theme": "코스피 200",
        "summary": "국내 대형주 시장을 대표하는 코스피200 추종 ETF입니다.",
        "expenseRatio": "0.15%대",
        "aum": "국내 대형",
        "distribution": "분배",
        "outlook": "반도체, 자동차, 금융 대형주의 이익 방향성이 핵심입니다.",
        "risk": "국내 경기와 수출 사이클, 원화 흐름, 반도체 대형주 집중도에 민감합니다.",
        "holdings": [
            {"ticker": "005930.KS", "name": "삼성전자", "weight": 0.235},
            {"ticker": "000660.KS", "name": "SK하이닉스", "weight": 0.094},
            {"ticker": "373220.KS", "name": "LG에너지솔루션", "weight": 0.038},
            {"ticker": "005380.KS", "name": "현대차", "weight": 0.031},
            {"ticker": "207940.KS", "name": "삼성바이오로직스", "weight": 0.029},
        ],
        "exposures": [
            {"label": "IT", "weight": 0.34},
            {"label": "산업재", "weight": 0.14},
            {"label": "금융", "weight": 0.12},
            {"label": "헬스케어", "weight": 0.09},
        ],
    },
    {
        "ticker": "VTI",
        "name": "Vanguard Total Stock Market",
        "region": "US",
        "category": "대표지수",
        "theme": "미국 전체시장",
        "summary": "대형주부터 중소형주까지 미국 주식시장 전반을 한 번에 담는 ETF입니다.",
        "expenseRatio": "0.03%",
        "aum": "$400B+",
        "distribution": "분기",
        "outlook": "미국 시장 전체를 코어로 가져가고 싶을 때 가장 단순한 선택지입니다.",
        "risk": "시총가중 구조라 대형 기술주 영향이 여전히 크고, 미국 시장 전체 조정에는 같이 흔들립니다.",
        "holdings": [
            {"ticker": "NVDA", "name": "NVIDIA", "weight": 0.066},
            {"ticker": "MSFT", "name": "Microsoft", "weight": 0.060},
            {"ticker": "AAPL", "name": "Apple", "weight": 0.055},
            {"ticker": "AMZN", "name": "Amazon", "weight": 0.034},
            {"ticker": "META", "name": "Meta", "weight": 0.026},
        ],
        "exposures": [
            {"label": "대형주", "weight": 0.73},
            {"label": "중형주", "weight": 0.18},
            {"label": "소형주", "weight": 0.09},
        ],
    },
    {
        "ticker": "IWM",
        "name": "iShares Russell 2000",
        "region": "US",
        "category": "소형주",
        "theme": "미국 소형주",
        "summary": "미국 중소형 기업 사이클에 투자하는 대표 ETF입니다.",
        "expenseRatio": "0.19%",
        "aum": "$60B+",
        "distribution": "분기",
        "outlook": "금리 하락, 경기 회복, 내수 민감주 반등 국면에서 탄력이 커질 수 있습니다.",
        "risk": "이익 변동성과 부채 부담이 큰 기업 비중이 높아 대형주보다 변동성이 큽니다.",
        "holdings": [
            {"ticker": "FTAI", "name": "FTAI Aviation", "weight": 0.007},
            {"ticker": "INSM", "name": "Insmed", "weight": 0.006},
            {"ticker": "SFM", "name": "Sprouts Farmers Market", "weight": 0.005},
            {"ticker": "AIT", "name": "Applied Industrial", "weight": 0.005},
            {"ticker": "CRS", "name": "Carpenter Technology", "weight": 0.005},
        ],
        "exposures": [
            {"label": "산업재", "weight": 0.18},
            {"label": "금융", "weight": 0.17},
            {"label": "헬스케어", "weight": 0.16},
            {"label": "경기소비재", "weight": 0.11},
        ],
    },
    {
        "ticker": "XLK",
        "name": "Technology Select Sector SPDR",
        "region": "US",
        "category": "섹터",
        "theme": "미국 기술주",
        "summary": "S&P 500 안의 대형 기술주를 집중적으로 담는 섹터 ETF입니다.",
        "expenseRatio": "0.09%대",
        "aum": "$60B+",
        "distribution": "분기",
        "outlook": "AI, 소프트웨어, 반도체 사이클이 강할 때 시장 대비 초과 성과를 노릴 수 있습니다.",
        "risk": "소수 대형주 집중도가 높아 기술주 밸류에이션 조정에 민감합니다.",
        "holdings": [
            {"ticker": "MSFT", "name": "Microsoft", "weight": 0.150},
            {"ticker": "AAPL", "name": "Apple", "weight": 0.145},
            {"ticker": "NVDA", "name": "NVIDIA", "weight": 0.130},
            {"ticker": "AVGO", "name": "Broadcom", "weight": 0.055},
            {"ticker": "CRM", "name": "Salesforce", "weight": 0.025},
        ],
        "exposures": [
            {"label": "소프트웨어", "weight": 0.36},
            {"label": "하드웨어", "weight": 0.24},
            {"label": "반도체", "weight": 0.24},
            {"label": "IT서비스", "weight": 0.12},
        ],
    },
    {
        "ticker": "XLF",
        "name": "Financial Select Sector SPDR",
        "region": "US",
        "category": "섹터",
        "theme": "미국 금융",
        "summary": "미국 은행, 카드, 보험, 거래소 기업을 담는 금융 섹터 ETF입니다.",
        "expenseRatio": "0.09%대",
        "aum": "$40B+",
        "distribution": "분기",
        "outlook": "장단기 금리차 개선과 신용 사이클 안정이 확인될 때 관심도가 높아집니다.",
        "risk": "경기 둔화, 부실채권 확대, 규제 이슈에 민감합니다.",
        "holdings": [
            {"ticker": "BRK-B", "name": "Berkshire Hathaway", "weight": 0.130},
            {"ticker": "JPM", "name": "JPMorgan Chase", "weight": 0.095},
            {"ticker": "V", "name": "Visa", "weight": 0.055},
            {"ticker": "MA", "name": "Mastercard", "weight": 0.045},
            {"ticker": "BAC", "name": "Bank of America", "weight": 0.040},
        ],
        "exposures": [
            {"label": "은행", "weight": 0.34},
            {"label": "자본시장", "weight": 0.24},
            {"label": "보험", "weight": 0.18},
            {"label": "결제", "weight": 0.14},
        ],
    },
    {
        "ticker": "XLV",
        "name": "Health Care Select Sector SPDR",
        "region": "US",
        "category": "섹터",
        "theme": "미국 헬스케어",
        "summary": "제약, 의료기기, 보험, 바이오를 담는 방어적 섹터 ETF입니다.",
        "expenseRatio": "0.09%대",
        "aum": "$35B+",
        "distribution": "분기",
        "outlook": "경기 민감도가 낮은 실적 안정성을 찾을 때 포트폴리오 완충재가 될 수 있습니다.",
        "risk": "약가 규제, 임상 실패, 대형 제약 특허만료 리스크를 함께 봐야 합니다.",
        "holdings": [
            {"ticker": "LLY", "name": "Eli Lilly", "weight": 0.120},
            {"ticker": "UNH", "name": "UnitedHealth", "weight": 0.080},
            {"ticker": "JNJ", "name": "Johnson & Johnson", "weight": 0.060},
            {"ticker": "ABBV", "name": "AbbVie", "weight": 0.050},
            {"ticker": "MRK", "name": "Merck", "weight": 0.045},
        ],
        "exposures": [
            {"label": "제약", "weight": 0.34},
            {"label": "의료보험", "weight": 0.20},
            {"label": "의료기기", "weight": 0.18},
            {"label": "바이오", "weight": 0.13},
        ],
    },
    {
        "ticker": "VNQ",
        "name": "Vanguard Real Estate",
        "region": "US",
        "category": "리츠",
        "theme": "미국 리츠",
        "summary": "미국 상장 리츠와 부동산 운영 기업에 분산 투자하는 ETF입니다.",
        "expenseRatio": "0.13%대",
        "aum": "$30B+",
        "distribution": "분기",
        "outlook": "금리 안정과 임대료 회복이 같이 나타날 때 배당형 자산으로 주목받습니다.",
        "risk": "금리 상승, 상업용 부동산 공실, 리파이낸싱 부담에 민감합니다.",
        "holdings": [
            {"ticker": "PLD", "name": "Prologis", "weight": 0.075},
            {"ticker": "AMT", "name": "American Tower", "weight": 0.060},
            {"ticker": "EQIX", "name": "Equinix", "weight": 0.050},
            {"ticker": "WELL", "name": "Welltower", "weight": 0.045},
            {"ticker": "SPG", "name": "Simon Property", "weight": 0.035},
        ],
        "exposures": [
            {"label": "산업/물류", "weight": 0.18},
            {"label": "데이터센터", "weight": 0.15},
            {"label": "헬스케어", "weight": 0.13},
            {"label": "리테일", "weight": 0.12},
        ],
    },
    {
        "ticker": "TLT",
        "name": "iShares 20+ Year Treasury Bond",
        "region": "US",
        "category": "채권",
        "theme": "미국 장기국채",
        "summary": "미국 장기 국채 가격에 투자하는 대표적인 듀레이션 ETF입니다.",
        "expenseRatio": "0.15%대",
        "aum": "$50B+",
        "distribution": "월",
        "outlook": "경기 둔화와 금리 하락 기대가 강해질 때 방어와 반등을 동시에 노릴 수 있습니다.",
        "risk": "장기금리 상승에는 가격 하락폭이 크게 나타날 수 있습니다.",
        "holdings": [
            {"ticker": "TLT", "name": "20년 이상 미국 국채", "weight": 0.420},
            {"ticker": "UST30", "name": "30년 미국 국채", "weight": 0.300},
            {"ticker": "UST20", "name": "20년 미국 국채", "weight": 0.220},
            {"ticker": "CASH", "name": "현금성 자산", "weight": 0.030},
        ],
        "exposures": [
            {"label": "장기국채", "weight": 0.94},
            {"label": "현금/기타", "weight": 0.06},
        ],
    },
    {
        "ticker": "GLD",
        "name": "SPDR Gold Shares",
        "region": "US",
        "category": "원자재",
        "theme": "금",
        "summary": "금 가격 노출을 얻기 위한 대표 원자재 ETF입니다.",
        "expenseRatio": "0.40%대",
        "aum": "$60B+",
        "distribution": "없음",
        "outlook": "달러 약세, 실질금리 하락, 지정학 리스크가 커질 때 방어 자산으로 쓰입니다.",
        "risk": "현금흐름이 없는 자산이라 실질금리 상승과 달러 강세에는 약할 수 있습니다.",
        "holdings": [
            {"ticker": "GLD", "name": "금 현물", "weight": 0.995},
            {"ticker": "CASH", "name": "현금/기타", "weight": 0.005},
        ],
        "exposures": [
            {"label": "금", "weight": 0.995},
            {"label": "현금/기타", "weight": 0.005},
        ],
    },
    {
        "ticker": "ARKK",
        "name": "ARK Innovation ETF",
        "region": "US",
        "category": "테마",
        "theme": "혁신성장",
        "summary": "AI, 전기차, 바이오, 핀테크 등 고성장 혁신 기업을 담는 테마 ETF입니다.",
        "expenseRatio": "0.75%대",
        "aum": "$5B+",
        "distribution": "연",
        "outlook": "성장주 위험선호가 강해질 때 높은 베타로 반등할 수 있습니다.",
        "risk": "높은 변동성, 편입종목 교체, 적자 성장주의 금리 민감도를 반드시 봐야 합니다.",
        "holdings": [
            {"ticker": "TSLA", "name": "Tesla", "weight": 0.110},
            {"ticker": "COIN", "name": "Coinbase", "weight": 0.080},
            {"ticker": "ROKU", "name": "Roku", "weight": 0.060},
            {"ticker": "SHOP", "name": "Shopify", "weight": 0.055},
            {"ticker": "CRSP", "name": "CRISPR Therapeutics", "weight": 0.040},
        ],
        "exposures": [
            {"label": "기술", "weight": 0.36},
            {"label": "헬스케어", "weight": 0.24},
            {"label": "경기소비재", "weight": 0.18},
            {"label": "금융기술", "weight": 0.12},
        ],
    },
    {
        "ticker": "133690",
        "name": "TIGER 미국나스닥100",
        "region": "KR",
        "category": "해외지수",
        "theme": "국내상장 나스닥 100",
        "summary": "국내 계좌로 나스닥100 대형 성장주에 접근하는 대표 ETF입니다.",
        "expenseRatio": "0.07%대",
        "aum": "국내 대형",
        "distribution": "상품별 상이",
        "outlook": "미국 성장주와 원달러 환율 흐름을 함께 보고 접근하기 좋습니다.",
        "risk": "국내장 거래 시간과 미국장 가격 반영 시차, 환율 변동을 같이 확인해야 합니다.",
        "holdings": [
            {"ticker": "NVDA", "name": "NVIDIA", "weight": 0.082},
            {"ticker": "MSFT", "name": "Microsoft", "weight": 0.078},
            {"ticker": "AAPL", "name": "Apple", "weight": 0.073},
            {"ticker": "AMZN", "name": "Amazon", "weight": 0.052},
            {"ticker": "AVGO", "name": "Broadcom", "weight": 0.047},
        ],
        "exposures": [
            {"label": "미국", "weight": 0.96},
            {"label": "현금/기타", "weight": 0.04},
        ],
    },
    {
        "ticker": "305720",
        "name": "KODEX 2차전지산업",
        "region": "KR",
        "category": "테마",
        "theme": "국내 2차전지",
        "summary": "배터리 셀, 소재, 장비 기업에 집중 투자하는 국내 테마 ETF입니다.",
        "expenseRatio": "0.45%대",
        "aum": "국내 중대형",
        "distribution": "상품별 상이",
        "outlook": "전기차 수요 회복과 소재 가격 안정이 확인될 때 반등 탄력이 커질 수 있습니다.",
        "risk": "업황 둔화, 가격 경쟁, 특정 대형주 집중도에 따라 변동성이 큽니다.",
        "holdings": [
            {"ticker": "373220.KS", "name": "LG에너지솔루션", "weight": 0.180},
            {"ticker": "006400.KS", "name": "삼성SDI", "weight": 0.120},
            {"ticker": "003670.KS", "name": "포스코퓨처엠", "weight": 0.090},
            {"ticker": "247540.KQ", "name": "에코프로비엠", "weight": 0.080},
            {"ticker": "005490.KS", "name": "POSCO홀딩스", "weight": 0.060},
        ],
        "exposures": [
            {"label": "셀", "weight": 0.32},
            {"label": "소재", "weight": 0.38},
            {"label": "장비", "weight": 0.14},
            {"label": "기타", "weight": 0.16},
        ],
    },
    {
        "ticker": "091160",
        "name": "KODEX 반도체",
        "region": "KR",
        "category": "섹터",
        "theme": "국내 반도체",
        "summary": "국내 반도체 대형주와 장비, 소재 기업을 함께 담는 섹터 ETF입니다.",
        "expenseRatio": "0.45%대",
        "aum": "국내 중대형",
        "distribution": "분배",
        "outlook": "메모리 업황 회복과 AI 서버 투자 확대가 핵심 모멘텀입니다.",
        "risk": "메모리 가격 사이클, 수출 규제, 대형주 비중 변화에 민감합니다.",
        "holdings": [
            {"ticker": "005930.KS", "name": "삼성전자", "weight": 0.230},
            {"ticker": "000660.KS", "name": "SK하이닉스", "weight": 0.210},
            {"ticker": "403870.KQ", "name": "HPSP", "weight": 0.055},
            {"ticker": "058470.KQ", "name": "리노공업", "weight": 0.050},
            {"ticker": "000990.KS", "name": "DB하이텍", "weight": 0.045},
        ],
        "exposures": [
            {"label": "메모리", "weight": 0.48},
            {"label": "장비", "weight": 0.24},
            {"label": "소재/부품", "weight": 0.18},
            {"label": "파운드리", "weight": 0.10},
        ],
    },
]


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def normalize_etf_item(item: dict) -> dict:
    ticker = str(item.get("ticker") or item.get("Ticker") or "").strip().upper()
    holdings = _first_etf_collection(item, "holdings", "Holdings")
    exposures = _first_etf_collection(item, "exposures", "Exposures")
    summary = str(item.get("summary") or item.get("Summary") or "").strip()
    return {
        "rank": safe_int(item.get("rank") or item.get("Rank")),
        "ticker": ticker,
        "name": str(item.get("name") or item.get("Name") or ticker).strip(),
        "region": str(item.get("region") or item.get("Market") or item.get("market") or "US").strip().upper(),
        "category": str(item.get("category") or item.get("Category") or "기타").strip(),
        "theme": str(item.get("theme") or item.get("Theme") or "").strip(),
        "summary": clean_etf_summary(summary),
        "expenseRatio": str(item.get("expenseRatio") or item.get("ExpenseRatio") or item.get("expense_ratio") or "").strip(),
        "aum": str(item.get("aum") or item.get("AUM") or "").strip(),
        "distribution": str(item.get("distribution") or item.get("Distribution") or "").strip(),
        "outlook": str(item.get("outlook") or item.get("Outlook") or "").strip(),
        "risk": str(item.get("risk") or item.get("Risk") or "").strip(),
        "holdings": normalize_holdings(holdings),
        "exposures": normalize_exposures(exposures),
        "currentPrice": first_safe_float(
            item.get("currentPrice"),
            item.get("Current_Price"),
            item.get("current_price"),
            item.get("price"),
            item.get("Price"),
        ),
        "return1M": first_safe_float(
            item.get("return1M"),
            item.get("Return_1M"),
            item.get("return_1m"),
            item.get("1M_Return"),
            item.get("Mom_1M"),
        ),
        "priceChange": first_safe_float(
            item.get("priceChange"),
            item.get("Price_Change"),
            item.get("price_change"),
        ),
        "asOf": str(item.get("asOf") or item.get("AsOf") or "").strip(),
        "dataSource": str(item.get("dataSource") or item.get("DataSource") or "").strip(),
    }


def _first_etf_collection(item: dict, *keys: str) -> Iterable[dict]:
    for key in keys:
        value = item.get(key)
        if value is not None:
            return value
    return []


def _normalize_etf_collection(items: Iterable[dict]) -> list[dict]:
    if isinstance(items, list):
        return items
    if isinstance(items, (str, bytes, dict)) or items is None:
        return []
    try:
        return list(items)
    except TypeError:
        return []


def normalize_holdings(items: Iterable[dict]) -> list[dict]:
    rows = []
    source_items = _normalize_etf_collection(items)
    for item in source_items:
        if not isinstance(item, dict):
            continue
        ticker = str(item.get("ticker") or item.get("Ticker") or "").strip().upper()
        if not ticker:
            continue
        rows.append({
            "ticker": ticker,
            "name": str(item.get("name") or item.get("Name") or ticker).strip(),
            "weight": safe_float(item.get("weight") or item.get("Weight")),
        })
    return rows


def normalize_exposures(items: Iterable[dict]) -> list[dict]:
    rows = []
    source_items = _normalize_etf_collection(items)
    for item in source_items:
        if not isinstance(item, dict):
            continue
        label = str(item.get("label") or item.get("Label") or "").strip()
        if not label:
            continue
        rows.append({
            "label": label,
            "weight": safe_float(item.get("weight") or item.get("Weight")),
        })
    return rows


def safe_float(value) -> float | None:
    try:
        return None if value in (None, "") else float(value)
    except (TypeError, ValueError):
        return None


def first_safe_float(*values) -> float | None:
    for value in values:
        number = safe_float(value)
        if number is not None:
            return number
    return None


def safe_int(value) -> int | None:
    try:
        return None if value in (None, "") else int(float(value))
    except (TypeError, ValueError):
        return None


def clean_etf_summary(summary: str) -> str:
    clean = str(summary or "")
    for token in ("국내상장 해외 ", "국내상장 ", "미국상장 ", "국내 상장 ", "미국 상장 "):
        clean = clean.replace(token, "")
    clean = (
        clean
        .replace("대표적인 해외 ETF", "대표 ETF")
        .replace("대표적인 ETF", "대표 ETF")
        .replace("해외 ETF", "ETF")
        .replace("ETF입니다.", "ETF")
        .replace("ETF입니다", "ETF")
    )
    while "  " in clean:
        clean = clean.replace("  ", " ")
    return clean.strip()


PROFILE_SEED_TICKERS = {
    "sp500": "VOO",
    "us_total": "VTI",
    "nasdaq100": "QQQ",
    "dividend": "SCHD",
    "semiconductor_us": "SOXX",
    "technology": "XLK",
    "financial": "XLF",
    "healthcare": "XLV",
    "reit_us": "VNQ",
    "realestate": "VNQ",
    "bond_long": "TLT",
    "gold": "GLD",
    "innovation": "ARKK",
    "russell2000": "IWM",
    "smallcap_us": "IWM",
    "kospi200": "069500",
    "sp500_kr": "379800",
    "nasdaq100_kr": "133690",
    "battery_kr": "305720",
    "semiconductor_kr": "091160",
}


PROFILE_COMPONENTS = {
    "dow30": {
        "holdings": [
            {"ticker": "UNH", "name": "UnitedHealth", "weight": 0.085},
            {"ticker": "GS", "name": "Goldman Sachs", "weight": 0.078},
            {"ticker": "MSFT", "name": "Microsoft", "weight": 0.063},
            {"ticker": "HD", "name": "Home Depot", "weight": 0.055},
            {"ticker": "CAT", "name": "Caterpillar", "weight": 0.050},
        ],
        "exposures": [
            {"label": "산업재", "weight": 0.22},
            {"label": "금융", "weight": 0.19},
            {"label": "기술", "weight": 0.18},
            {"label": "헬스케어", "weight": 0.16},
        ],
    },
    "midcap_us": {
        "holdings": [
            {"ticker": "WSM", "name": "Williams-Sonoma", "weight": 0.009},
            {"ticker": "DECK", "name": "Deckers Outdoor", "weight": 0.008},
            {"ticker": "RS", "name": "Reliance", "weight": 0.007},
            {"ticker": "WSO", "name": "Watsco", "weight": 0.007},
            {"ticker": "MANH", "name": "Manhattan Associates", "weight": 0.006},
        ],
        "exposures": [
            {"label": "산업재", "weight": 0.20},
            {"label": "금융", "weight": 0.16},
            {"label": "경기소비재", "weight": 0.14},
            {"label": "기술", "weight": 0.12},
        ],
    },
    "global_equity": {
        "holdings": [
            {"ticker": "NVDA", "name": "NVIDIA", "weight": 0.045},
            {"ticker": "MSFT", "name": "Microsoft", "weight": 0.040},
            {"ticker": "AAPL", "name": "Apple", "weight": 0.035},
            {"ticker": "AMZN", "name": "Amazon", "weight": 0.022},
            {"ticker": "ASML", "name": "ASML", "weight": 0.010},
        ],
        "exposures": [
            {"label": "미국", "weight": 0.62},
            {"label": "선진국", "weight": 0.28},
            {"label": "신흥국", "weight": 0.10},
        ],
    },
    "developed_ex_us": {
        "holdings": [
            {"ticker": "ASML", "name": "ASML", "weight": 0.025},
            {"ticker": "NOVO-B", "name": "Novo Nordisk", "weight": 0.020},
            {"ticker": "SAP", "name": "SAP", "weight": 0.018},
            {"ticker": "NESN", "name": "Nestle", "weight": 0.016},
            {"ticker": "SHEL", "name": "Shell", "weight": 0.015},
        ],
        "exposures": [
            {"label": "유럽", "weight": 0.55},
            {"label": "일본", "weight": 0.22},
            {"label": "태평양", "weight": 0.13},
            {"label": "기타", "weight": 0.10},
        ],
    },
    "emerging": {
        "holdings": [
            {"ticker": "TSM", "name": "TSMC", "weight": 0.080},
            {"ticker": "TCEHY", "name": "Tencent", "weight": 0.035},
            {"ticker": "BABA", "name": "Alibaba", "weight": 0.025},
            {"ticker": "005930.KS", "name": "삼성전자", "weight": 0.020},
            {"ticker": "RELIANCE", "name": "Reliance Industries", "weight": 0.015},
        ],
        "exposures": [
            {"label": "대만", "weight": 0.24},
            {"label": "인도", "weight": 0.20},
            {"label": "중국", "weight": 0.19},
            {"label": "한국", "weight": 0.11},
        ],
    },
    "growth": {
        "holdings": [
            {"ticker": "NVDA", "name": "NVIDIA", "weight": 0.115},
            {"ticker": "MSFT", "name": "Microsoft", "weight": 0.105},
            {"ticker": "AAPL", "name": "Apple", "weight": 0.095},
            {"ticker": "AMZN", "name": "Amazon", "weight": 0.060},
            {"ticker": "META", "name": "Meta", "weight": 0.045},
        ],
        "exposures": [
            {"label": "기술", "weight": 0.48},
            {"label": "커뮤니케이션", "weight": 0.16},
            {"label": "경기소비재", "weight": 0.15},
            {"label": "헬스케어", "weight": 0.08},
        ],
    },
    "value": {
        "holdings": [
            {"ticker": "BRK-B", "name": "Berkshire Hathaway", "weight": 0.035},
            {"ticker": "JPM", "name": "JPMorgan Chase", "weight": 0.028},
            {"ticker": "XOM", "name": "Exxon Mobil", "weight": 0.026},
            {"ticker": "JNJ", "name": "Johnson & Johnson", "weight": 0.022},
            {"ticker": "PG", "name": "Procter & Gamble", "weight": 0.020},
        ],
        "exposures": [
            {"label": "금융", "weight": 0.22},
            {"label": "헬스케어", "weight": 0.15},
            {"label": "산업재", "weight": 0.14},
            {"label": "에너지", "weight": 0.11},
        ],
    },
    "momentum": {
        "holdings": [
            {"ticker": "NVDA", "name": "NVIDIA", "weight": 0.060},
            {"ticker": "AVGO", "name": "Broadcom", "weight": 0.035},
            {"ticker": "LLY", "name": "Eli Lilly", "weight": 0.030},
            {"ticker": "META", "name": "Meta", "weight": 0.028},
            {"ticker": "COST", "name": "Costco", "weight": 0.022},
        ],
        "exposures": [
            {"label": "기술", "weight": 0.34},
            {"label": "헬스케어", "weight": 0.16},
            {"label": "경기소비재", "weight": 0.14},
            {"label": "산업재", "weight": 0.12},
        ],
    },
    "quality": {
        "holdings": [
            {"ticker": "MSFT", "name": "Microsoft", "weight": 0.055},
            {"ticker": "AAPL", "name": "Apple", "weight": 0.050},
            {"ticker": "NVDA", "name": "NVIDIA", "weight": 0.045},
            {"ticker": "V", "name": "Visa", "weight": 0.025},
            {"ticker": "MA", "name": "Mastercard", "weight": 0.022},
        ],
        "exposures": [
            {"label": "기술", "weight": 0.38},
            {"label": "금융", "weight": 0.16},
            {"label": "헬스케어", "weight": 0.12},
            {"label": "산업재", "weight": 0.10},
        ],
    },
    "lowvol": {
        "holdings": [
            {"ticker": "PG", "name": "Procter & Gamble", "weight": 0.025},
            {"ticker": "KO", "name": "Coca-Cola", "weight": 0.022},
            {"ticker": "PEP", "name": "PepsiCo", "weight": 0.020},
            {"ticker": "MCD", "name": "McDonald's", "weight": 0.018},
            {"ticker": "WMT", "name": "Walmart", "weight": 0.017},
        ],
        "exposures": [
            {"label": "필수소비재", "weight": 0.25},
            {"label": "헬스케어", "weight": 0.18},
            {"label": "유틸리티", "weight": 0.15},
            {"label": "금융", "weight": 0.12},
        ],
    },
    "covered_call": {
        "holdings": [
            {"ticker": "ELN", "name": "Equity Linked Notes", "weight": 0.160},
            {"ticker": "MSFT", "name": "Microsoft", "weight": 0.060},
            {"ticker": "NVDA", "name": "NVIDIA", "weight": 0.055},
            {"ticker": "AAPL", "name": "Apple", "weight": 0.050},
            {"ticker": "AMZN", "name": "Amazon", "weight": 0.030},
        ],
        "exposures": [
            {"label": "주식", "weight": 0.78},
            {"label": "옵션 프리미엄", "weight": 0.18},
            {"label": "현금/기타", "weight": 0.04},
        ],
    },
    "energy": {
        "holdings": [
            {"ticker": "XOM", "name": "Exxon Mobil", "weight": 0.220},
            {"ticker": "CVX", "name": "Chevron", "weight": 0.160},
            {"ticker": "COP", "name": "ConocoPhillips", "weight": 0.070},
            {"ticker": "EOG", "name": "EOG Resources", "weight": 0.045},
            {"ticker": "SLB", "name": "SLB", "weight": 0.040},
        ],
        "exposures": [
            {"label": "통합에너지", "weight": 0.50},
            {"label": "탐사/생산", "weight": 0.26},
            {"label": "장비/서비스", "weight": 0.14},
            {"label": "정유/운송", "weight": 0.10},
        ],
    },
    "consumer_discretionary": {
        "holdings": [
            {"ticker": "AMZN", "name": "Amazon", "weight": 0.210},
            {"ticker": "TSLA", "name": "Tesla", "weight": 0.130},
            {"ticker": "HD", "name": "Home Depot", "weight": 0.070},
            {"ticker": "MCD", "name": "McDonald's", "weight": 0.045},
            {"ticker": "LOW", "name": "Lowe's", "weight": 0.035},
        ],
        "exposures": [
            {"label": "인터넷소매", "weight": 0.28},
            {"label": "자동차", "weight": 0.18},
            {"label": "소매", "weight": 0.18},
            {"label": "서비스", "weight": 0.14},
        ],
    },
    "consumer_staples": {
        "holdings": [
            {"ticker": "COST", "name": "Costco", "weight": 0.110},
            {"ticker": "WMT", "name": "Walmart", "weight": 0.100},
            {"ticker": "PG", "name": "Procter & Gamble", "weight": 0.095},
            {"ticker": "KO", "name": "Coca-Cola", "weight": 0.070},
            {"ticker": "PEP", "name": "PepsiCo", "weight": 0.065},
        ],
        "exposures": [
            {"label": "소매", "weight": 0.32},
            {"label": "가정용품", "weight": 0.22},
            {"label": "음료", "weight": 0.18},
            {"label": "식품", "weight": 0.16},
        ],
    },
    "industrial": {
        "holdings": [
            {"ticker": "GE", "name": "GE Aerospace", "weight": 0.060},
            {"ticker": "CAT", "name": "Caterpillar", "weight": 0.055},
            {"ticker": "RTX", "name": "RTX", "weight": 0.050},
            {"ticker": "UBER", "name": "Uber", "weight": 0.045},
            {"ticker": "HON", "name": "Honeywell", "weight": 0.040},
        ],
        "exposures": [
            {"label": "항공/방산", "weight": 0.28},
            {"label": "기계", "weight": 0.22},
            {"label": "운송", "weight": 0.18},
            {"label": "전기장비", "weight": 0.12},
        ],
    },
    "utility": {
        "holdings": [
            {"ticker": "NEE", "name": "NextEra Energy", "weight": 0.130},
            {"ticker": "SO", "name": "Southern", "weight": 0.080},
            {"ticker": "DUK", "name": "Duke Energy", "weight": 0.075},
            {"ticker": "CEG", "name": "Constellation Energy", "weight": 0.070},
            {"ticker": "SRE", "name": "Sempra", "weight": 0.045},
        ],
        "exposures": [
            {"label": "전력", "weight": 0.62},
            {"label": "복합유틸리티", "weight": 0.22},
            {"label": "가스", "weight": 0.08},
            {"label": "수도", "weight": 0.04},
        ],
    },
    "materials": {
        "holdings": [
            {"ticker": "LIN", "name": "Linde", "weight": 0.180},
            {"ticker": "SHW", "name": "Sherwin-Williams", "weight": 0.070},
            {"ticker": "APD", "name": "Air Products", "weight": 0.055},
            {"ticker": "ECL", "name": "Ecolab", "weight": 0.050},
            {"ticker": "FCX", "name": "Freeport-McMoRan", "weight": 0.045},
        ],
        "exposures": [
            {"label": "화학", "weight": 0.48},
            {"label": "금속/광업", "weight": 0.20},
            {"label": "포장재", "weight": 0.14},
            {"label": "건자재", "weight": 0.10},
        ],
    },
    "communication": {
        "holdings": [
            {"ticker": "META", "name": "Meta", "weight": 0.230},
            {"ticker": "GOOGL", "name": "Alphabet Class A", "weight": 0.120},
            {"ticker": "GOOG", "name": "Alphabet Class C", "weight": 0.100},
            {"ticker": "NFLX", "name": "Netflix", "weight": 0.050},
            {"ticker": "TMUS", "name": "T-Mobile US", "weight": 0.045},
        ],
        "exposures": [
            {"label": "인터넷플랫폼", "weight": 0.50},
            {"label": "미디어", "weight": 0.22},
            {"label": "통신", "weight": 0.18},
            {"label": "게임", "weight": 0.06},
        ],
    },
    "biotech": {
        "holdings": [
            {"ticker": "VRTX", "name": "Vertex", "weight": 0.080},
            {"ticker": "REGN", "name": "Regeneron", "weight": 0.075},
            {"ticker": "AMGN", "name": "Amgen", "weight": 0.065},
            {"ticker": "GILD", "name": "Gilead", "weight": 0.060},
            {"ticker": "MRNA", "name": "Moderna", "weight": 0.030},
        ],
        "exposures": [
            {"label": "바이오", "weight": 0.86},
            {"label": "제약", "weight": 0.10},
            {"label": "현금/기타", "weight": 0.04},
        ],
    },
    "robotics": {
        "holdings": [
            {"ticker": "NVDA", "name": "NVIDIA", "weight": 0.070},
            {"ticker": "ISRG", "name": "Intuitive Surgical", "weight": 0.055},
            {"ticker": "ROK", "name": "Rockwell Automation", "weight": 0.045},
            {"ticker": "ABBNY", "name": "ABB", "weight": 0.040},
            {"ticker": "TER", "name": "Teradyne", "weight": 0.035},
        ],
        "exposures": [
            {"label": "로보틱스", "weight": 0.38},
            {"label": "AI 반도체", "weight": 0.24},
            {"label": "자동화", "weight": 0.22},
            {"label": "의료기기", "weight": 0.10},
        ],
    },
    "cyber": {
        "holdings": [
            {"ticker": "PANW", "name": "Palo Alto Networks", "weight": 0.075},
            {"ticker": "CRWD", "name": "CrowdStrike", "weight": 0.070},
            {"ticker": "FTNT", "name": "Fortinet", "weight": 0.060},
            {"ticker": "ZS", "name": "Zscaler", "weight": 0.045},
            {"ticker": "OKTA", "name": "Okta", "weight": 0.035},
        ],
        "exposures": [
            {"label": "보안소프트웨어", "weight": 0.76},
            {"label": "네트워크", "weight": 0.14},
            {"label": "클라우드", "weight": 0.10},
        ],
    },
    "cloud": {
        "holdings": [
            {"ticker": "MSFT", "name": "Microsoft", "weight": 0.070},
            {"ticker": "SNOW", "name": "Snowflake", "weight": 0.050},
            {"ticker": "DDOG", "name": "Datadog", "weight": 0.045},
            {"ticker": "NET", "name": "Cloudflare", "weight": 0.040},
            {"ticker": "CRM", "name": "Salesforce", "weight": 0.035},
        ],
        "exposures": [
            {"label": "클라우드 SW", "weight": 0.58},
            {"label": "데이터", "weight": 0.20},
            {"label": "인프라", "weight": 0.14},
            {"label": "보안", "weight": 0.08},
        ],
    },
    "clean_energy": {
        "holdings": [
            {"ticker": "FSLR", "name": "First Solar", "weight": 0.090},
            {"ticker": "ENPH", "name": "Enphase Energy", "weight": 0.055},
            {"ticker": "SEDG", "name": "SolarEdge", "weight": 0.035},
            {"ticker": "NEE", "name": "NextEra Energy", "weight": 0.030},
            {"ticker": "PLUG", "name": "Plug Power", "weight": 0.025},
        ],
        "exposures": [
            {"label": "태양광", "weight": 0.45},
            {"label": "전력/유틸리티", "weight": 0.20},
            {"label": "수소", "weight": 0.12},
            {"label": "장비", "weight": 0.10},
        ],
    },
    "bond_core": {
        "holdings": [
            {"ticker": "UST", "name": "미국 국채", "weight": 0.420},
            {"ticker": "MBS", "name": "기관 MBS", "weight": 0.260},
            {"ticker": "IG", "name": "투자등급 회사채", "weight": 0.230},
            {"ticker": "CASH", "name": "현금성 자산", "weight": 0.030},
        ],
        "exposures": [
            {"label": "국채", "weight": 0.42},
            {"label": "MBS", "weight": 0.26},
            {"label": "회사채", "weight": 0.23},
            {"label": "기타", "weight": 0.09},
        ],
    },
    "bond_intermediate": {
        "holdings": [
            {"ticker": "UST10", "name": "미국 중기국채", "weight": 0.900},
            {"ticker": "CASH", "name": "현금성 자산", "weight": 0.040},
        ],
        "exposures": [
            {"label": "중기국채", "weight": 0.92},
            {"label": "현금/기타", "weight": 0.08},
        ],
    },
    "bond_short": {
        "holdings": [
            {"ticker": "UST2", "name": "미국 단기국채", "weight": 0.920},
            {"ticker": "CASH", "name": "현금성 자산", "weight": 0.040},
        ],
        "exposures": [
            {"label": "단기국채", "weight": 0.94},
            {"label": "현금/기타", "weight": 0.06},
        ],
    },
    "credit_ig": {
        "holdings": [
            {"ticker": "IGCORP", "name": "투자등급 회사채", "weight": 0.920},
            {"ticker": "CASH", "name": "현금성 자산", "weight": 0.030},
        ],
        "exposures": [
            {"label": "A등급 이상", "weight": 0.46},
            {"label": "BBB", "weight": 0.43},
            {"label": "현금/기타", "weight": 0.11},
        ],
    },
    "credit_hy": {
        "holdings": [
            {"ticker": "HYCORP", "name": "하이일드 회사채", "weight": 0.910},
            {"ticker": "CASH", "name": "현금성 자산", "weight": 0.040},
        ],
        "exposures": [
            {"label": "BB", "weight": 0.48},
            {"label": "B", "weight": 0.34},
            {"label": "CCC 이하", "weight": 0.08},
            {"label": "현금/기타", "weight": 0.10},
        ],
    },
    "tips": {
        "holdings": [
            {"ticker": "TIPS", "name": "물가연동국채", "weight": 0.940},
            {"ticker": "CASH", "name": "현금성 자산", "weight": 0.030},
        ],
        "exposures": [
            {"label": "물가연동국채", "weight": 0.95},
            {"label": "현금/기타", "weight": 0.05},
        ],
    },
    "cash": {
        "holdings": [
            {"ticker": "TBILL", "name": "미국 초단기 국채", "weight": 0.960},
            {"ticker": "CASH", "name": "현금성 자산", "weight": 0.020},
        ],
        "exposures": [
            {"label": "초단기국채", "weight": 0.96},
            {"label": "현금/기타", "weight": 0.04},
        ],
    },
    "silver": {
        "holdings": [
            {"ticker": "SLV", "name": "은 현물", "weight": 0.995},
            {"ticker": "CASH", "name": "현금/기타", "weight": 0.005},
        ],
        "exposures": [
            {"label": "은", "weight": 0.995},
            {"label": "현금/기타", "weight": 0.005},
        ],
    },
    "oil": {
        "holdings": [
            {"ticker": "WTI", "name": "WTI 원유 선물", "weight": 0.940},
            {"ticker": "CASH", "name": "현금성 자산", "weight": 0.040},
        ],
        "exposures": [
            {"label": "원유", "weight": 0.94},
            {"label": "현금/기타", "weight": 0.06},
        ],
    },
    "commodity": {
        "holdings": [
            {"ticker": "ENERGY", "name": "에너지 선물", "weight": 0.420},
            {"ticker": "GOLD", "name": "귀금속", "weight": 0.190},
            {"ticker": "AGRI", "name": "농산물", "weight": 0.170},
            {"ticker": "METAL", "name": "산업금속", "weight": 0.120},
        ],
        "exposures": [
            {"label": "에너지", "weight": 0.42},
            {"label": "귀금속", "weight": 0.19},
            {"label": "농산물", "weight": 0.17},
            {"label": "산업금속", "weight": 0.12},
        ],
    },
    "kosdaq150": {
        "holdings": [
            {"ticker": "247540.KQ", "name": "에코프로비엠", "weight": 0.070},
            {"ticker": "086520.KQ", "name": "에코프로", "weight": 0.055},
            {"ticker": "196170.KQ", "name": "알테오젠", "weight": 0.045},
            {"ticker": "028300.KQ", "name": "HLB", "weight": 0.040},
            {"ticker": "068760.KQ", "name": "셀트리온제약", "weight": 0.035},
        ],
        "exposures": [
            {"label": "헬스케어", "weight": 0.26},
            {"label": "IT", "weight": 0.24},
            {"label": "2차전지", "weight": 0.18},
            {"label": "소재/산업재", "weight": 0.12},
        ],
    },
    "leverage": {
        "holdings": [
            {"ticker": "K200F", "name": "코스피200 선물", "weight": 0.850},
            {"ticker": "CASH", "name": "현금성 자산", "weight": 0.120},
        ],
        "exposures": [
            {"label": "파생", "weight": 0.84},
            {"label": "현금/기타", "weight": 0.16},
        ],
    },
    "inverse": {
        "holdings": [
            {"ticker": "K200F-SHORT", "name": "코스피200 선물 매도", "weight": 0.840},
            {"ticker": "CASH", "name": "현금성 자산", "weight": 0.130},
        ],
        "exposures": [
            {"label": "인버스 파생", "weight": 0.84},
            {"label": "현금/기타", "weight": 0.16},
        ],
    },
    "fang_kr": {
        "holdings": [
            {"ticker": "NVDA", "name": "NVIDIA", "weight": 0.110},
            {"ticker": "META", "name": "Meta", "weight": 0.100},
            {"ticker": "AMZN", "name": "Amazon", "weight": 0.095},
            {"ticker": "GOOGL", "name": "Alphabet", "weight": 0.090},
            {"ticker": "TSLA", "name": "Tesla", "weight": 0.085},
        ],
        "exposures": [
            {"label": "미국 빅테크", "weight": 0.94},
            {"label": "현금/기타", "weight": 0.06},
        ],
    },
    "semiconductor_us_kr": {
        "holdings": [
            {"ticker": "NVDA", "name": "NVIDIA", "weight": 0.110},
            {"ticker": "AVGO", "name": "Broadcom", "weight": 0.085},
            {"ticker": "AMD", "name": "AMD", "weight": 0.070},
            {"ticker": "QCOM", "name": "Qualcomm", "weight": 0.055},
            {"ticker": "ASML", "name": "ASML", "weight": 0.050},
        ],
        "exposures": [
            {"label": "미국 반도체", "weight": 0.92},
            {"label": "현금/기타", "weight": 0.08},
        ],
    },
    "europe": {
        "holdings": [
            {"ticker": "ASML", "name": "ASML", "weight": 0.055},
            {"ticker": "SAP", "name": "SAP", "weight": 0.050},
            {"ticker": "NOVO-B", "name": "Novo Nordisk", "weight": 0.045},
            {"ticker": "MC", "name": "LVMH", "weight": 0.030},
            {"ticker": "SIE", "name": "Siemens", "weight": 0.026},
        ],
        "exposures": [
            {"label": "유럽 대형주", "weight": 0.94},
            {"label": "현금/기타", "weight": 0.06},
        ],
    },
    "currency_usd": {
        "holdings": [
            {"ticker": "USDKRW", "name": "미국달러 선물", "weight": 0.930},
            {"ticker": "CASH", "name": "현금성 자산", "weight": 0.050},
        ],
        "exposures": [
            {"label": "달러", "weight": 0.93},
            {"label": "현금/기타", "weight": 0.07},
        ],
    },
    "bond_kr": {
        "holdings": [
            {"ticker": "KTB3Y", "name": "국고채 3년", "weight": 0.540},
            {"ticker": "KTB10Y", "name": "국고채 10년", "weight": 0.260},
            {"ticker": "KRCORP", "name": "우량 회사채", "weight": 0.140},
            {"ticker": "CASH", "name": "현금성 자산", "weight": 0.030},
        ],
        "exposures": [
            {"label": "국고채", "weight": 0.80},
            {"label": "회사채", "weight": 0.14},
            {"label": "현금/기타", "weight": 0.06},
        ],
    },
    "bank_kr": {
        "holdings": [
            {"ticker": "055550.KS", "name": "신한지주", "weight": 0.190},
            {"ticker": "105560.KS", "name": "KB금융", "weight": 0.180},
            {"ticker": "086790.KS", "name": "하나금융지주", "weight": 0.150},
            {"ticker": "316140.KS", "name": "우리금융지주", "weight": 0.100},
            {"ticker": "024110.KS", "name": "기업은행", "weight": 0.075},
        ],
        "exposures": [
            {"label": "은행지주", "weight": 0.72},
            {"label": "은행", "weight": 0.18},
            {"label": "현금/기타", "weight": 0.10},
        ],
    },
    "construction_kr": {
        "holdings": [
            {"ticker": "028050.KS", "name": "삼성E&A", "weight": 0.140},
            {"ticker": "000720.KS", "name": "현대건설", "weight": 0.125},
            {"ticker": "047040.KS", "name": "대우건설", "weight": 0.090},
            {"ticker": "006360.KS", "name": "GS건설", "weight": 0.080},
            {"ticker": "375500.KS", "name": "DL이앤씨", "weight": 0.060},
        ],
        "exposures": [
            {"label": "건설", "weight": 0.72},
            {"label": "엔지니어링", "weight": 0.18},
            {"label": "현금/기타", "weight": 0.10},
        ],
    },
    "transport_kr": {
        "holdings": [
            {"ticker": "003490.KS", "name": "대한항공", "weight": 0.190},
            {"ticker": "086280.KS", "name": "현대글로비스", "weight": 0.140},
            {"ticker": "011200.KS", "name": "HMM", "weight": 0.120},
            {"ticker": "180640.KS", "name": "한진칼", "weight": 0.070},
            {"ticker": "089590.KS", "name": "제주항공", "weight": 0.045},
        ],
        "exposures": [
            {"label": "항공", "weight": 0.38},
            {"label": "해운", "weight": 0.24},
            {"label": "물류", "weight": 0.22},
            {"label": "현금/기타", "weight": 0.16},
        ],
    },
    "media_kr": {
        "holdings": [
            {"ticker": "035760.KQ", "name": "CJ ENM", "weight": 0.120},
            {"ticker": "352820.KS", "name": "하이브", "weight": 0.110},
            {"ticker": "041510.KQ", "name": "에스엠", "weight": 0.080},
            {"ticker": "122870.KQ", "name": "와이지엔터테인먼트", "weight": 0.070},
            {"ticker": "253450.KQ", "name": "스튜디오드래곤", "weight": 0.055},
        ],
        "exposures": [
            {"label": "엔터", "weight": 0.42},
            {"label": "콘텐츠", "weight": 0.34},
            {"label": "미디어", "weight": 0.12},
            {"label": "현금/기타", "weight": 0.12},
        ],
    },
    "travel_kr": {
        "holdings": [
            {"ticker": "008770.KS", "name": "호텔신라", "weight": 0.100},
            {"ticker": "039130.KS", "name": "하나투어", "weight": 0.090},
            {"ticker": "032350.KQ", "name": "롯데관광개발", "weight": 0.075},
            {"ticker": "003490.KS", "name": "대한항공", "weight": 0.070},
            {"ticker": "089590.KS", "name": "제주항공", "weight": 0.050},
        ],
        "exposures": [
            {"label": "여행", "weight": 0.32},
            {"label": "항공", "weight": 0.24},
            {"label": "면세/호텔", "weight": 0.22},
            {"label": "레저", "weight": 0.12},
        ],
    },
    "bbig_kr": {
        "holdings": [
            {"ticker": "005930.KS", "name": "삼성전자", "weight": 0.090},
            {"ticker": "000660.KS", "name": "SK하이닉스", "weight": 0.085},
            {"ticker": "373220.KS", "name": "LG에너지솔루션", "weight": 0.075},
            {"ticker": "035420.KS", "name": "NAVER", "weight": 0.060},
            {"ticker": "207940.KS", "name": "삼성바이오로직스", "weight": 0.055},
        ],
        "exposures": [
            {"label": "배터리", "weight": 0.25},
            {"label": "바이오", "weight": 0.25},
            {"label": "인터넷", "weight": 0.25},
            {"label": "게임", "weight": 0.25},
        ],
    },
    "reit_kr": {
        "holdings": [
            {"ticker": "365550.KS", "name": "ESR켄달스퀘어리츠", "weight": 0.160},
            {"ticker": "330590.KS", "name": "롯데리츠", "weight": 0.130},
            {"ticker": "348950.KS", "name": "제이알글로벌리츠", "weight": 0.115},
            {"ticker": "357120.KS", "name": "코람코라이프인프라리츠", "weight": 0.090},
            {"ticker": "088980.KS", "name": "맥쿼리인프라", "weight": 0.080},
        ],
        "exposures": [
            {"label": "리츠", "weight": 0.68},
            {"label": "인프라", "weight": 0.20},
            {"label": "현금/기타", "weight": 0.12},
        ],
    },
    "china_ev": {
        "holdings": [
            {"ticker": "BYD", "name": "BYD", "weight": 0.095},
            {"ticker": "CATL", "name": "CATL", "weight": 0.090},
            {"ticker": "LI", "name": "Li Auto", "weight": 0.055},
            {"ticker": "NIO", "name": "NIO", "weight": 0.035},
            {"ticker": "XPEV", "name": "XPeng", "weight": 0.030},
        ],
        "exposures": [
            {"label": "전기차", "weight": 0.45},
            {"label": "배터리", "weight": 0.34},
            {"label": "부품", "weight": 0.12},
            {"label": "현금/기타", "weight": 0.09},
        ],
    },
}


PROFILE_OUTLOOK = {
    "대표지수": "시장 전체의 이익 방향성과 위험선호가 살아날 때 코어 자산으로 쓰기 좋습니다.",
    "해외지수": "해외 증시 흐름과 환율 변화를 함께 확인할 때 활용도가 높습니다.",
    "섹터": "해당 업종의 이익 모멘텀과 밸류에이션이 동시에 개선될 때 탄력이 커질 수 있습니다.",
    "테마": "테마 수급과 실적 확인이 함께 나타날 때 강하게 움직일 수 있습니다.",
    "배당": "금리 안정과 현금흐름 선호가 강해질 때 방어적 역할을 기대할 수 있습니다.",
    "채권": "금리 방향성과 신용 스프레드가 수익률을 크게 좌우합니다.",
    "원자재": "달러와 실질금리, 수급 사이클을 함께 볼 때 의미가 커집니다.",
    "리츠": "금리 안정과 임대 수익 회복이 확인될 때 관심도가 높아집니다.",
    "전략": "단기 방향성이나 변동성 관리 목적에 맞춰 짧게 점검하는 성격이 강합니다.",
}


PROFILE_RISK = {
    "대표지수": "시장 전체 조정에는 같이 하락하며, 시총가중 구조에서는 대형주 집중도가 커질 수 있습니다.",
    "해외지수": "해외장과 국내장 시차, 환율, 환헤지 여부를 함께 확인해야 합니다.",
    "섹터": "업종 사이클과 규제, 특정 대형주 비중 변화에 따라 변동성이 커질 수 있습니다.",
    "테마": "장기 성장성은 좋아도 단기 수급과 기대감 변화에 매우 민감할 수 있습니다.",
    "배당": "성장주 랠리 구간에서는 상대 성과가 약할 수 있고 배당 정책 변화도 봐야 합니다.",
    "채권": "금리 상승기에는 가격 하락이 나타나며, 회사채형은 신용 리스크도 함께 봐야 합니다.",
    "원자재": "현금흐름이 없는 자산이 많아 달러 강세와 실질금리 상승에 약할 수 있습니다.",
    "리츠": "금리 상승, 공실률, 리파이낸싱 부담이 동시에 리스크로 작용할 수 있습니다.",
    "전략": "레버리지와 인버스 상품은 장기 보유 시 복리 효과로 추적 오차가 커질 수 있습니다.",
}


def profile_components(profile: str) -> dict:
    key = str(profile or "").strip()
    if key in PROFILE_COMPONENTS:
        return {
            "holdings": normalize_holdings(PROFILE_COMPONENTS[key].get("holdings", [])),
            "exposures": normalize_exposures(PROFILE_COMPONENTS[key].get("exposures", [])),
        }
    seed_ticker = PROFILE_SEED_TICKERS.get(key)
    if seed_ticker:
        for item in ETF_INSIGHTS:
            if str(item.get("ticker", "")).upper() == seed_ticker:
                normal = normalize_etf_item(item)
                return {
                    "holdings": normal["holdings"],
                    "exposures": normal["exposures"],
                }
    return {"holdings": [], "exposures": []}


def json_list(value: str) -> list[dict]:
    text = str(value or "").strip()
    if not text:
        return []
    try:
        parsed = json.loads(text)
        return parsed if isinstance(parsed, list) else []
    except json.JSONDecodeError:
        return []


def curated_summary(row: dict) -> str:
    theme = str(row.get("theme") or row.get("Theme") or row.get("name") or row.get("Name") or "").strip()
    return f"{theme} 노출을 제공하는 ETF"


def curated_outlook(row: dict) -> str:
    category = str(row.get("category") or row.get("Category") or "기타").strip()
    return PROFILE_OUTLOOK.get(category, "가격 모멘텀, 비용, 구성종목 집중도를 함께 확인하면 좋습니다.")


def curated_risk(row: dict) -> str:
    category = str(row.get("category") or row.get("Category") or "기타").strip()
    return PROFILE_RISK.get(category, "기초자산 변동성과 유동성, 환율 영향을 함께 점검해야 합니다.")


def csv_etf_record(row: dict, index: int) -> dict:
    profile = str(row.get("profile") or row.get("Profile") or "").strip()
    components = profile_components(profile)
    holdings = normalize_holdings(json_list(row.get("holdings") or row.get("Holdings")) or components["holdings"])
    exposures = normalize_exposures(json_list(row.get("exposures") or row.get("Exposures")) or components["exposures"])
    ticker = str(row.get("ticker") or row.get("Ticker") or "").strip().upper()
    return {
        "rank": safe_int(row.get("rank") or row.get("Rank")) or index,
        "ticker": ticker,
        "name": str(row.get("name") or row.get("Name") or ticker).strip(),
        "region": str(row.get("region") or row.get("Market") or "US").strip().upper(),
        "category": str(row.get("category") or row.get("Category") or "기타").strip(),
        "theme": str(row.get("theme") or row.get("Theme") or "").strip(),
        "summary": clean_etf_summary(str(row.get("summary") or row.get("Summary") or "").strip() or curated_summary(row)),
        "expenseRatio": str(row.get("expenseRatio") or row.get("ExpenseRatio") or row.get("expense_ratio") or "").strip(),
        "aum": str(row.get("aum") or row.get("AUM") or "").strip(),
        "distribution": str(row.get("distribution") or row.get("Distribution") or "").strip(),
        "outlook": str(row.get("outlook") or row.get("Outlook") or "").strip() or curated_outlook(row),
        "risk": str(row.get("risk") or row.get("Risk") or "").strip() or curated_risk(row),
        "holdings": holdings,
        "exposures": exposures,
        "currentPrice": first_safe_float(
            row.get("currentPrice"),
            row.get("Current_Price"),
            row.get("current_price"),
            row.get("price"),
            row.get("Price"),
        ),
        "return1M": first_safe_float(
            row.get("return1M"),
            row.get("Return_1M"),
            row.get("return_1m"),
            row.get("1M_Return"),
            row.get("Mom_1M"),
        ),
        "priceChange": first_safe_float(
            row.get("priceChange"),
            row.get("Price_Change"),
            row.get("price_change"),
        ),
        "asOf": str(row.get("asOf") or row.get("AsOf") or "").strip(),
        "dataSource": str(row.get("dataSource") or row.get("DataSource") or "curated_universe").strip(),
        "profile": profile,
    }


@lru_cache(maxsize=4)
def load_etf_universe_records(path: str | Path = ETF_UNIVERSE_CSV) -> list[dict]:
    source_path = Path(path)
    if not source_path.exists():
        return []
    with source_path.open(newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        return [csv_etf_record(row, index) for index, row in enumerate(reader, start=1)]


def default_etf_insights(path: str | Path = ETF_UNIVERSE_CSV) -> list[dict]:
    return load_etf_universe_records(path) or ETF_INSIGHTS


def default_etf_source(path: str | Path = ETF_UNIVERSE_CSV) -> str:
    return "csv_seed" if load_etf_universe_records(path) else "bundled_seed"


def records_with_seed_fallback(records: list[dict] | None) -> tuple[list[dict], str]:
    if not records:
        return default_etf_insights(), default_etf_source()

    seed = default_etf_insights()
    merged: dict[str, dict] = {}
    for item in seed:
        ticker = str(item.get("ticker") or item.get("Ticker") or "").strip().upper()
        if ticker:
            merged[ticker] = item
    for item in records:
        ticker = str(item.get("ticker") or item.get("Ticker") or "").strip().upper()
        if ticker:
            merged[ticker] = item

    source = "storage"
    if len(merged) > len(records):
        source = f"storage+{default_etf_source()}"
    return list(merged.values()), source


def payload_from_records(
    records: list[dict] | None = None,
    *,
    market: str = "ALL",
    category: str = "ALL",
    q: str = "",
    limit: int = 500,
) -> dict:
    source_records, source = records_with_seed_fallback(records)
    items = [normalize_etf_item(item) for item in source_records]
    items = [item for item in items if item["ticker"]]
    safe_market = str(market or "ALL").upper()
    safe_category = str(category or "ALL")
    clean_q = str(q or "").strip().lower()

    if safe_market != "ALL":
        items = [item for item in items if item["region"].upper() == safe_market]
    if safe_category != "ALL":
        items = [item for item in items if item["category"] == safe_category]
    if clean_q:
        items = [
            item for item in items
            if clean_q in item["ticker"].lower()
            or clean_q in item["name"].lower()
            or clean_q in item["theme"].lower()
            or clean_q in item["category"].lower()
        ]

    safe_limit = max(1, min(int(limit or 500), 1000))
    items = [
        item for _, item in sorted(
            enumerate(items),
            key=lambda pair: pair[1].get("rank") or pair[0] + 1,
        )
    ]
    return {
        "items": items[:safe_limit],
        "count": len(items),
        "updatedAt": utc_now_iso(),
        "source": source,
    }


def detail_from_records(records: list[dict] | None, ticker: str) -> dict | None:
    normal = str(ticker or "").strip().upper()
    if not normal:
        return None
    for item in payload_from_records(records, limit=1000)["items"]:
        if item["ticker"].upper() == normal:
            return {
                "item": item,
                "updatedAt": utc_now_iso(),
                "source": "storage" if records else default_etf_source(),
            }
    return None
