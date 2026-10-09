from __future__ import annotations

import re

_CJK_RE = re.compile(r"[\u4e00-\u9fff\u3400-\u4dbf\uf900-\ufaff]")
_ASCII_RE = re.compile(r"[A-Za-z0-9]")
_SYMBOL_RE = re.compile(r"[^\u4e00-\u9fff\u3400-\u4dbf\uf900-\ufaffA-Za-z0-9\s]")
_WHITESPACE_RE = re.compile(r"\s+")

def estimate_text_tokens(text: str | None) -> int:
    if not text:
        return 0
    cjk = len(_CJK_RE.findall(text))
    ascii_chars = len(_ASCII_RE.findall(text))
    symbols = len(_SYMBOL_RE.findall(text))
    whitespace = len(_WHITESPACE_RE.findall(text))
    ascii_tokens = (ascii_chars + 3) // 4
    return cjk * 2 + ascii_tokens + symbols + whitespace

def estimate_messages_tokens(messages: list[dict]) -> int:
    total = 0
    for msg in messages:
        total += estimate_text_tokens(msg.get("content"))
        total += 4
    return total
