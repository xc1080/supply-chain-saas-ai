from app.config.settings import Settings
from app.services.llm_factory import _resolve_memory_llm_config, create_chat_llm, create_memory_llm

def test_memory_llm_falls_back_to_chat_config(monkeypatch):
    s = Settings(
        llm_api_key="chat-key",
        llm_base_url="https://chat.example",
        llm_model="chat-model",
        llm_timeout=60,
    )
    api_key, base_url, model, timeout = _resolve_memory_llm_config(s)
    assert api_key == "chat-key"
    assert base_url == "https://chat.example"
    assert model == "chat-model"
    assert timeout == 60

def test_memory_llm_uses_dedicated_config(monkeypatch):
    s = Settings(
        llm_api_key="chat-key",
        llm_base_url="https://chat.example",
        llm_model="chat-model",
        memory_llm_api_key="mem-key",
        memory_llm_base_url="https://mem.example",
        memory_llm_model="mem-model",
        memory_llm_timeout=90,
    )
    api_key, base_url, model, timeout = _resolve_memory_llm_config(s)
    assert api_key == "mem-key"
    assert base_url == "https://mem.example"
    assert model == "mem-model"
    assert timeout == 90

def test_chat_and_memory_llm_streaming_flag():
    chat = create_chat_llm()
    memory = create_memory_llm()
    assert chat.streaming is True
    assert memory.streaming is False
