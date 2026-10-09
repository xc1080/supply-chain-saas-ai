from fastapi import Request

from fastapi.responses import JSONResponse

from app.exceptions import BusinessException

from app.models.response import error

async def business_exception_handler(_: Request, exc: BusinessException) -> JSONResponse:

    body = error(exc.code, exc.message)

    if exc.data is not None:

        body.data = exc.data

    return JSONResponse(status_code=200, content=body.model_dump())
