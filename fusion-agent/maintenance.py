"""Periodic local maintenance with observable failures and orderly shutdown.

Callbacks use short SQLite transactions. Multiple processes may run them: the
database predicates and write locks, rather than a process flag, guard state.
"""
from __future__ import annotations

import asyncio
import logging
import os
import time


class PeriodicMaintenance:
    def __init__(self, callback, *, name, interval=None):
        self.callback = callback
        self.name = name
        self.interval = max(0.01, float(interval if interval is not None else
                                      os.getenv("FUSION_MAINTENANCE_INTERVAL_SECONDS", "60")))
        self._task = None
        self._stop = None
        self.last_success = None
        self.last_failure = None
        self.failures = 0
        self.last_result = None

    async def start(self):
        if self._task is not None and not self._task.done():
            return
        self._stop = asyncio.Event()
        self._task = asyncio.create_task(self._run(), name="maintenance:" + self.name)

    async def _run(self):
        while not self._stop.is_set():
            try:
                # No blocking SQLite work on the ASGI event loop. Shutdown waits
                # for an in-flight transaction before closing dependencies.
                self.last_result = await asyncio.to_thread(self.callback)
                self.last_success = time.time()
            except Exception:
                self.failures += 1
                self.last_failure = time.time()
                # Never log SQL parameters, questions, identity or credentials.
                logging.getLogger(__name__).warning("Maintenance callback failed: %s", self.name)
            try:
                await asyncio.wait_for(self._stop.wait(), timeout=self.interval)
            except asyncio.TimeoutError:
                pass

    async def close(self):
        task = self._task
        if task is None:
            return
        self._stop.set()
        await task
        self._task = None

    def snapshot(self):
        return {"running": self._task is not None and not self._task.done(),
                "lastSuccess": self.last_success, "lastFailure": self.last_failure,
                "failures": self.failures, "lastResult": self.last_result}
