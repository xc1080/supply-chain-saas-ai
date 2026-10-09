from fastapi import Request

from app.auth.token_service import TokenUserInfo, require_login

__all__ = ["TokenUserInfo", "require_login", "get_request_token"]

def get_request_token(request: Request) -> str | None:

    return request.headers.get("token") or request.cookies.get("token")
