"""Shared internal-token gate (.claude/rules/ai-service.md: "Internal
endpoints require the INTERNAL_SERVICE_TOKEN header. The service is not
public."). One FastAPI dependency, reused by every internal router so the
check can't drift between endpoints."""

from typing import Annotated

from fastapi import Depends, Header, HTTPException, status

from app.config import Settings, get_settings


def verify_internal_token(
    settings: Annotated[Settings, Depends(get_settings)],
    x_internal_service_token: Annotated[str | None, Header()] = None,
) -> None:
    expected = settings.internal_service_token
    if not expected or x_internal_service_token != expected:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="unauthorized")
