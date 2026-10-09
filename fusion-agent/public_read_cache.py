"""Bounded process-local cache for public read projections, never checkout decisions."""
from __future__ import annotations

import asyncio
import copy
import time


class PublicReadCache:
    def __init__(self, ttl: float):
        self.ttl = max(0.0, ttl)
        self._entries: dict[str, tuple[float, object]] = {}
        self._locks: dict[str, asyncio.Lock] = {}
        self._generation = 0

    async def get(self, key, load):
        if not self.ttl:
            return await load()
        entry = self._entries.get(key)
        if entry and entry[0] > time.monotonic():
            return copy.deepcopy(entry[1])
        async with self._locks.setdefault(key, asyncio.Lock()):
            entry = self._entries.get(key)
            if entry and entry[0] > time.monotonic():
                return copy.deepcopy(entry[1])
            generation = self._generation
            value = await load()
            # A successful write can invalidate while this read is in flight.
            if generation == self._generation:
                self._entries[key] = (time.monotonic() + self.ttl, copy.deepcopy(value))
            return value

    def invalidate(self):
        self._generation += 1
        self._entries.clear()
