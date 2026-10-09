from typing import Any, Generic, TypeVar

from pydantic import BaseModel

T = TypeVar("T")

class ResponseVO(BaseModel, Generic[T]):

    status: str = "success"

    code: int = 200

    info: str | None = None

    data: T | None = None

def success(data: Any = None, info: str | None = None) -> ResponseVO:

    return ResponseVO(data=data, info=info)

def error(code: int, info: str) -> ResponseVO:

    return ResponseVO(status="error", code=code, info=info)
