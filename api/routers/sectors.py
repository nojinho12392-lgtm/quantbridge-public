from fastapi import APIRouter

from api.contracts.mobile_v1 import SectorThemeDetailResponse, SectorThemesResponse


def create_sector_router(service) -> APIRouter:
    router = APIRouter()

    @router.get("/sectors/themes/summary", response_model=SectorThemesResponse)
    def sector_theme_summary(market: str = "ALL", limit: int = 36, refresh: bool = False):
        return service.sector_theme_summary(market=market, limit=limit, refresh=refresh)

    @router.get("/sectors/themes/detail", response_model=SectorThemeDetailResponse)
    def sector_theme_detail(label: str, market: str = "ALL", members: int = 200, refresh: bool = False):
        return service.sector_theme_detail(label=label, market=market, members=members, refresh=refresh)

    @router.get("/sectors/themes", response_model=SectorThemesResponse)
    def sector_themes(market: str = "ALL", limit: int = 36, members: int = 120, refresh: bool = False):
        return service.sector_themes(market=market, limit=limit, members=members, refresh=refresh)

    return router
