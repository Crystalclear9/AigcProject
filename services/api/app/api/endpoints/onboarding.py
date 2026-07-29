from fastapi import APIRouter

from app.schemas.onboarding import OnboardingTurnRequest, OnboardingTurnResponse
from app.services.onboarding_service import run_onboarding_turn

router = APIRouter()


@router.post("/turn", response_model=OnboardingTurnResponse)
async def onboarding_turn(request: OnboardingTurnRequest) -> OnboardingTurnResponse:
    return await run_onboarding_turn(request)

