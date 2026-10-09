import asyncio
import unittest

from public_read_cache import PublicReadCache


class PublicReadCacheTests(unittest.IsolatedAsyncioTestCase):
    async def test_concurrent_misses_share_one_authority_read_and_copies_are_isolated(self):
        cache = PublicReadCache(1)
        reads = 0

        async def load():
            nonlocal reads
            reads += 1
            await asyncio.sleep(.01)
            return [{"remaining": 10}]

        values = await asyncio.gather(*(cache.get("activities", load) for _ in range(100)))
        self.assertEqual(reads, 1)
        values[0][0]["remaining"] = 0
        self.assertEqual((await cache.get("activities", load))[0]["remaining"], 10)

    async def test_failed_read_is_not_cached_or_served_as_stale_authority(self):
        cache = PublicReadCache(1)

        async def down():
            raise ConnectionError("authority unavailable")

        with self.assertRaises(ConnectionError):
            await cache.get("catalog", down)
        self.assertEqual(await cache.get("catalog", lambda: asyncio.sleep(0, result=[1])), [1])

    async def test_invalidation_during_inflight_read_prevents_repopulating_old_data(self):
        cache = PublicReadCache(1)
        started, finish = asyncio.Event(), asyncio.Event()

        async def old_read():
            started.set()
            await finish.wait()
            return "old"

        old = asyncio.create_task(cache.get("catalog", old_read))
        await started.wait()
        cache.invalidate()
        finish.set()
        self.assertEqual(await old, "old")
        self.assertEqual(await cache.get("catalog", lambda: asyncio.sleep(0, result="new")), "new")

    async def test_expiry_reloads_and_different_router_caches_never_mix(self):
        a, b = PublicReadCache(.01), PublicReadCache(1)
        self.assertEqual(await a.get("catalog", lambda: asyncio.sleep(0, result="tenant-a")), "tenant-a")
        self.assertEqual(await b.get("catalog", lambda: asyncio.sleep(0, result="tenant-b")), "tenant-b")
        await asyncio.sleep(.02)
        self.assertEqual(await a.get("catalog", lambda: asyncio.sleep(0, result="new-a")), "new-a")


if __name__ == "__main__":
    unittest.main()
