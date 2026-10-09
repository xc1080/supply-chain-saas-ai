import time
from enum import Enum

import structlog

logger = structlog.get_logger()

class CircuitState(str, Enum):

    CLOSED = "closed"
    OPEN = "open"
    HALF_OPEN = "half_open"

class CircuitBreaker:

    def __init__(
        self,
        name: str,
        failure_threshold: int = 5,
        recovery_timeout: int = 60,
    ):

        self.name = name
        self.failure_threshold = failure_threshold
        self.recovery_timeout = recovery_timeout
        self._state = CircuitState.CLOSED
        self._failure_count = 0
        self._last_failure_time: float = 0

    @property
    def state(self) -> CircuitState:

        if self._state == CircuitState.OPEN:
            if time.time() - self._last_failure_time >= self.recovery_timeout:
                self._state = CircuitState.HALF_OPEN
                logger.info("circuit_half_open", breaker=self.name)
        return self._state

    def allow_request(self) -> bool:

        return self.state != CircuitState.OPEN

    def record_success(self) -> None:

        self._failure_count = 0
        if self._state != CircuitState.CLOSED:
            logger.info("circuit_closed", breaker=self.name)
        self._state = CircuitState.CLOSED

    def record_failure(self) -> None:

        self._failure_count += 1
        self._last_failure_time = time.time()
        if self._failure_count >= self.failure_threshold:
            self._state = CircuitState.OPEN
            logger.warning(
                "circuit_open",
                breaker=self.name,
                failures=self._failure_count,
            )

    async def sync_to_redis(self, redis_client, key: str) -> None:

        await redis_client.hset(
            key,
            mapping={
                "state": self._state.value,
                "failures": str(self._failure_count),
                "last_failure": str(self._last_failure_time),
            },
        )

    async def load_from_redis(self, redis_client, key: str) -> None:

        data = await redis_client.hgetall(key)
        if not data:
            return
        self._state = CircuitState(data.get(b"state", b"closed").decode())
        self._failure_count = int(data.get(b"failures", b"0"))
        self._last_failure_time = float(data.get(b"last_failure", b"0"))

class CircuitBreakerRegistry:

    def __init__(self):
        self._breakers: dict[str, CircuitBreaker] = {}

    def get_or_create(
        self,
        name: str,
        failure_threshold: int = 5,
        recovery_timeout: int = 60,
    ) -> CircuitBreaker:

        if name not in self._breakers:
            self._breakers[name] = CircuitBreaker(name, failure_threshold, recovery_timeout)
        return self._breakers[name]

circuit_registry = CircuitBreakerRegistry()
