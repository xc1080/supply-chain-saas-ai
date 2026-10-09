import json
import re
from dataclasses import dataclass

from fastapi import Cookie, Header, Request

from app.constants import REDIS_TOKEN_WEB, TOKEN_COOKIE_NAME, TOKEN_HEADER_NAME
from app.exceptions import BusinessException
from app.services.redis_service import redis_service

@dataclass
class TokenUserInfo:

    user_id: str
    email: str | None = None
    nick_name: str | None = None
    avatar: str | None = None
    token: str | None = None

def _parse_spring_json(raw: str | bytes) -> dict:

    if isinstance(raw, bytes):
        raw = raw.decode("utf-8", errors="ignore")
    text = raw.strip()
    if not text.startswith("{"):
        start = text.find("{")
        if start >= 0:
            text = text[start:]
    return json.loads(text)

async def get_user_by_token(token: str) -> TokenUserInfo | None:

    if not token:
        return None

    raw = await redis_service.client.get(f"{REDIS_TOKEN_WEB}{token}")
    if not raw:
        return None
    try:
        data = _parse_spring_json(raw)
    except (json.JSONDecodeError, TypeError):

        m = re.search(r'"userId"\s*:\s*"([^"]+)"', str(raw))
        if not m:
            return None
        return TokenUserInfo(user_id=m.group(1), token=token)

    return TokenUserInfo(
        user_id=data.get("userId") or data.get("user_id", ""),
        email=data.get("email"),
        nick_name=data.get("nickName") or data.get("nick_name"),
        avatar=data.get("avatar"),
        token=data.get("token") or token,
    )

def resolve_token(
    request: Request,
    token_header: str | None = None,
    token_cookie: str | None = None,
) -> str | None:

    if token_header:
        return token_header.strip()
    if token_cookie:
        return token_cookie.strip()

    return request.headers.get(TOKEN_HEADER_NAME) or request.cookies.get(TOKEN_COOKIE_NAME)

async def require_login(
    request: Request,
    token: str | None = Header(None, alias="token"),
    cookie_token: str | None = Cookie(None, alias="token"),
) -> TokenUserInfo:

    resolved = resolve_token(request, token, cookie_token)
    if not resolved:
        raise BusinessException(901, "登录超时，请重新登录")
    user = await get_user_by_token(resolved)
    if not user or not user.user_id:
        raise BusinessException(901, "登录超时，请重新登录")
    return user
