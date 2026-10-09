from prometheus_client import Counter, Gauge, Histogram

INTENT_TOTAL = Counter(
    "agent_intent_total",
    "意图识别次数",
    ["intent", "source"],
)

RAG_HIT_RATE = Gauge("agent_rag_hit_rate", "RAG 检索命中率")

LLM_LATENCY = Histogram(
    "agent_llm_latency_seconds",
    "LLM 调用延迟",
    buckets=[0.5, 1, 2, 3, 5, 10, 30],
)

CIRCUIT_STATE = Gauge(
    "agent_circuit_state",
    "熔断器状态 0=closed 1=open 2=half_open",
    ["breaker"],
)

TOOL_CALL_TOTAL = Counter(
    "agent_tool_call_total",
    "工具调用次数",
    ["tool", "status"],
)

STREAM_TOKENS = Counter("agent_stream_tokens_total", "流式输出 token 数")
