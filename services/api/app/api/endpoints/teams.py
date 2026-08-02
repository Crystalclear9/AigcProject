from __future__ import annotations

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Response

from app.api.deps import get_card_repository, get_team_repository
from app.repositories.cards import CardRepository, utc_now
from app.repositories.teams import TeamRepository
from app.schemas.card import ActionCard, ActionCardCreate
from app.schemas.team import (
    GoalConfirmRequest,
    GoalConfirmResponse,
    GoalDecomposition,
    GoalProgress,
    MemberStat,
    MilestoneProgress,
    ProposedTask,
    Team,
    TeamCreate,
    TeamGoal,
    TeamGoalCreate,
    TeamJoinRequest,
    TeamRename,
    TeamSummary,
)
from app.services.team_goal_service import decompose_goal

router = APIRouter()


def require_user_id(x_user_id: str = Header(alias="X-User-Id")) -> str:
    if not x_user_id.strip():
        raise HTTPException(status_code=422, detail="X-User-Id header required")
    return x_user_id.strip()


def _guard(action):
    try:
        return action()
    except PermissionError as exc:
        raise HTTPException(status_code=403, detail=str(exc)) from exc
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="not found") from exc


@router.post("", response_model=Team, summary="Create a team")
def create_team(
    payload: TeamCreate,
    user_id: str = Depends(require_user_id),
    repo: TeamRepository = Depends(get_team_repository),
) -> Team:
    return _guard(lambda: repo.create_team(payload.name, user_id))


@router.post("/join", response_model=Team, summary="Join a team by invite code")
def join_team(
    payload: TeamJoinRequest,
    user_id: str = Depends(require_user_id),
    repo: TeamRepository = Depends(get_team_repository),
) -> Team:
    return _guard(lambda: repo.join_team(payload.invite_code, user_id))


@router.get("", response_model=list[Team], summary="List teams the caller belongs to")
def list_teams(
    user_id: str = Depends(require_user_id),
    repo: TeamRepository = Depends(get_team_repository),
) -> list[Team]:
    return repo.list_teams(user_id)


@router.get("/{team_id}", response_model=Team, summary="Team detail with members")
def get_team(
    team_id: str,
    user_id: str = Depends(require_user_id),
    repo: TeamRepository = Depends(get_team_repository),
) -> Team:
    def action() -> Team:
        team = repo.get_team(team_id)
        repo.require_member(team_id, user_id)
        return team

    return _guard(action)


@router.patch("/{team_id}", response_model=Team, summary="Rename a team (owner only)")
def rename_team(
    team_id: str,
    payload: TeamRename,
    user_id: str = Depends(require_user_id),
    repo: TeamRepository = Depends(get_team_repository),
) -> Team:
    def action() -> Team:
        repo.require_owner(team_id, user_id)
        return repo.rename_team(team_id, payload.name)

    return _guard(action)


@router.delete("/{team_id}", summary="Dissolve a team (owner only)")
def delete_team(
    team_id: str,
    user_id: str = Depends(require_user_id),
    repo: TeamRepository = Depends(get_team_repository),
) -> Response:
    def action() -> None:
        repo.require_owner(team_id, user_id)
        repo.delete_team(team_id)

    _guard(action)
    return Response(status_code=204)


@router.delete(
    "/{team_id}/members/{member_id}",
    response_model=Team,
    summary="Remove a member (owner only)",
)
def remove_member(
    team_id: str,
    member_id: str,
    user_id: str = Depends(require_user_id),
    repo: TeamRepository = Depends(get_team_repository),
) -> Team:
    def action() -> Team:
        repo.require_owner(team_id, user_id)
        return repo.remove_member(team_id, member_id)

    return _guard(action)


@router.post(
    "/{team_id}/goals",
    response_model=GoalDecomposition,
    summary="Create a goal and return the AI decomposition preview (owner only)",
)
async def create_goal(
    team_id: str,
    payload: TeamGoalCreate,
    user_id: str = Depends(require_user_id),
    repo: TeamRepository = Depends(get_team_repository),
) -> GoalDecomposition:
    def check() -> Team:
        team = repo.get_team(team_id)
        repo.require_owner(team_id, user_id)
        return team

    team = _guard(check)
    members = [
        {"user_id": member.user_id, "nickname": member.nickname}
        for member in team.members
    ]
    decomposition, source, warnings = await decompose_goal(
        title=payload.title,
        description=payload.description,
        due_date=payload.due_date,
        members=members,
    )
    goal = repo.create_goal(
        team_id, payload, user_id, decomposition["milestones"], source
    )
    milestone_ids = [milestone.id for milestone in goal.milestones]
    tasks = [
        ProposedTask(
            title=task["title"],
            summary=task.get("summary", ""),
            assignee_id=task.get("assignee_id"),
            milestone_id=(
                milestone_ids[task["milestone_index"]]
                if isinstance(task.get("milestone_index"), int)
                and 0 <= task["milestone_index"] < len(milestone_ids)
                else None
            ),
            start_time=task.get("start_date"),
            deadline=task.get("due_date"),
            deliverables=task.get("deliverables", []),
        )
        for task in decomposition["tasks"]
    ]
    return GoalDecomposition(goal=goal, tasks=tasks, warnings=warnings)


@router.get(
    "/{team_id}/goals",
    response_model=list[TeamGoal],
    summary="List goals of a team",
)
def list_goals(
    team_id: str,
    user_id: str = Depends(require_user_id),
    repo: TeamRepository = Depends(get_team_repository),
) -> list[TeamGoal]:
    def action() -> list[TeamGoal]:
        repo.get_team(team_id)
        repo.require_member(team_id, user_id)
        return repo.list_goals(team_id)

    return _guard(action)


@router.post(
    "/{team_id}/goals/{goal_id}/confirm",
    response_model=GoalConfirmResponse,
    summary="Confirm the decomposition and create team task cards (owner only)",
)
def confirm_goal(
    team_id: str,
    goal_id: str,
    payload: GoalConfirmRequest,
    user_id: str = Depends(require_user_id),
    repo: TeamRepository = Depends(get_team_repository),
    cards: CardRepository = Depends(get_card_repository),
) -> GoalConfirmResponse:
    def check() -> TeamGoal:
        repo.require_owner(team_id, user_id)
        goal = repo.get_goal(goal_id)
        if goal.team_id != team_id:
            raise KeyError(goal_id)
        return goal

    goal = _guard(check)
    if not payload.tasks:
        raise HTTPException(status_code=422, detail="tasks must not be empty")
    milestone_ids = {milestone.id for milestone in goal.milestones}
    created: list[ActionCard] = []
    for task in payload.tasks:
        created.append(
            cards.create(
                ActionCardCreate(
                    card_type="task",
                    title=task.title,
                    summary=task.summary,
                    deadline=task.deadline,
                    start_time=task.start_time,
                    deliverables=task.deliverables,
                    workspace_type="team",
                    workspace_id=team_id,
                    assignee_id=task.assignee_id,
                    milestone_id=(
                        task.milestone_id
                        if task.milestone_id in milestone_ids
                        else None
                    ),
                    status="confirmed",
                    source_text=f"团队目标：{goal.title}",
                    tags=["团队"],
                )
            )
        )
    return GoalConfirmResponse(goal=goal, cards=created)


@router.get(
    "/{team_id}/summary",
    response_model=TeamSummary,
    summary="Aggregated team progress for polling clients",
)
def team_summary(
    team_id: str,
    since: str | None = Query(default=None),
    user_id: str = Depends(require_user_id),
    repo: TeamRepository = Depends(get_team_repository),
    cards: CardRepository = Depends(get_card_repository),
) -> TeamSummary:
    def check() -> Team:
        team = repo.get_team(team_id)
        repo.require_member(team_id, user_id)
        return team

    team = _guard(check)
    server_time = utc_now().isoformat()
    all_cards = cards.list_for_workspace(team_id)
    active_cards = [card for card in all_cards if card.status != "archived"]

    def is_done(card: ActionCard) -> bool:
        return card.status == "done"

    goals: list[GoalProgress] = []
    for goal in repo.list_goals(team_id):
        milestone_progress = []
        for milestone in goal.milestones:
            scoped = [c for c in active_cards if c.milestone_id == milestone.id]
            milestone_progress.append(
                MilestoneProgress(
                    milestone=milestone,
                    done=sum(1 for c in scoped if is_done(c)),
                    total=len(scoped),
                )
            )
        milestone_ids = {m.id for m in goal.milestones}
        scoped = [c for c in active_cards if c.milestone_id in milestone_ids]
        goals.append(
            GoalProgress(
                goal=goal,
                done=sum(1 for c in scoped if is_done(c)),
                total=len(scoped),
                milestones=milestone_progress,
            )
        )

    member_stats = []
    for member in team.members:
        owned = [c for c in active_cards if c.assignee_id == member.user_id]
        member_stats.append(
            MemberStat(
                user_id=member.user_id,
                nickname=member.nickname,
                avatar_color=member.avatar_color,
                role=member.role,
                done=sum(1 for c in owned if is_done(c)),
                total=len(owned),
            )
        )

    if since:
        changed = [
            card
            for card in all_cards
            if card.updated_at and card.updated_at.isoformat() > since
        ]
    else:
        changed = all_cards
    return TeamSummary(
        team=team,
        goals=goals,
        member_stats=member_stats,
        changed_cards=changed,
        server_time=server_time,
    )
