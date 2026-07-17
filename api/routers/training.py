from fastapi import APIRouter

from api.contracts.mobile_v1 import BlindFinancialQuizResponse


def create_training_router(service) -> APIRouter:
    router = APIRouter(prefix="/training")

    @router.get("/blind-financial-quiz", response_model=BlindFinancialQuizResponse)
    def blind_financial_quiz(quiz_id: str = "", market: str = "US", refresh: bool = False):
        return service.blind_financial_quiz(quiz_id=quiz_id, market=market, refresh=refresh)

    return router
