from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException

from app.api.deps import get_team_repository
from app.repositories.teams import TeamRepository
from app.schemas.team import User, UserCreate, UserUpdate

router = APIRouter()


@router.post("", response_model=User, summary="Register or refresh a lightweight identity")
def upsert_user(
    user: UserCreate,
    repo: TeamRepository = Depends(get_team_repository),
) -> User:
    return repo.upsert_user(user)


@router.get("/{user_id}", response_model=User, summary="Fetch a user profile")
def get_user(
    user_id: str,
    repo: TeamRepository = Depends(get_team_repository),
) -> User:
    try:
        return repo.get_user(user_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="user not found") from exc


@router.patch("/{user_id}", response_model=User, summary="Update nickname or avatar color")
def update_user(
    user_id: str,
    patch: UserUpdate,
    repo: TeamRepository = Depends(get_team_repository),
) -> User:
    try:
        return repo.update_user(user_id, patch)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="user not found") from exc
