from fastapi import APIRouter

from api.contracts.mobile_v1 import NewsIssuesResponse


def create_news_router(service) -> APIRouter:
    router = APIRouter()

    @router.get("/news/issues", response_model=NewsIssuesResponse)
    def news_issues(q: str = "", market: str = "ALL", limit: int = 30):
        return service.news_issues(q=q, market=market, limit=limit)

    return router
