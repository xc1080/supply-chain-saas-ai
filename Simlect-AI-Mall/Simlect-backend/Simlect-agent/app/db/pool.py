from contextlib import asynccontextmanager

import aiomysql

from app.config.settings import get_settings

_pool: aiomysql.Pool | None = None

async def init_pool() -> None:

    global _pool

    settings = get_settings()

    _pool = await aiomysql.create_pool(

        host=settings.mysql_host,

        port=settings.mysql_port,

        user=settings.mysql_user,

        password=settings.mysql_password,

        db=settings.mysql_database,

        charset="utf8mb4",

        autocommit=True,

        minsize=2,

        maxsize=20,
    )

async def close_pool() -> None:

    global _pool

    if _pool:

        _pool.close()

        await _pool.wait_closed()

        _pool = None

@asynccontextmanager
async def acquire():

    if not _pool:

        raise RuntimeError("DB pool not initialized")

    async with _pool.acquire() as conn:

        async with conn.cursor(aiomysql.DictCursor) as cur:

            yield cur
