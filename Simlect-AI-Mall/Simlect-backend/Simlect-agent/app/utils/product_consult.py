import json
import re

CONSULT_PREFIX = "<<<PRODUCT_CONSULT>>>"
CONSULT_SUFFIX = "<<<END_CARD>>>"

def parse_consult_card(message: str) -> tuple[dict | None, str]:

    if not message or CONSULT_PREFIX not in message:
        return None, message or ""
    start = message.index(CONSULT_PREFIX) + len(CONSULT_PREFIX)
    end = message.find(CONSULT_SUFFIX, start)
    if end < 0:
        return None, message
    json_part = message[start:end].strip()
    user_text = message[end + len(CONSULT_SUFFIX):].strip()
    try:
        card = json.loads(json_part)
        return card, user_text
    except json.JSONDecodeError:
        return None, user_text or message

def build_consult_card_message(card: dict, user_text: str = "") -> str:

    return f"{CONSULT_PREFIX}{json.dumps(card, ensure_ascii=False)}{CONSULT_SUFFIX}{user_text}"

def normalize_consult_card(card: dict | None) -> dict | None:

    if not card:
        return None
    pid = card.get("productId") or card.get("product_id")
    if not pid:
        return None
    return {
        "productId": str(pid),
        "productName": card.get("productName") or card.get("product_name"),
        "minPrice": card.get("minPrice") or card.get("min_price"),
        "cover": card.get("cover"),
        "categoryId": card.get("categoryId") or card.get("category_id"),
    }

def is_product_consult_turn(
    user_text: str | None,
    message_card: dict | None = None,
    consult_card: dict | None = None,
    from_product: bool | None = None,
) -> bool:

    from app.domain.intent.rules import (
        looks_like_category_switch,
        looks_like_consult_followup,
        looks_like_new_product_search,
    )
    from app.services.product_service import is_similar_or_recommend_request

    if normalize_consult_card(message_card):
        consult_name = (normalize_consult_card(message_card) or {}).get("productName")
    else:
        consult = normalize_consult_card(consult_card)
        consult_name = (consult or {}).get("productName")

    if is_similar_or_recommend_request(user_text):
        return False
    if looks_like_category_switch(user_text, consult_name):
        return False
    if looks_like_new_product_search(user_text):
        return False
    if normalize_consult_card(message_card):
        return True
    if from_product is False:
        return False

    consult = normalize_consult_card(consult_card)
    if consult:

        return looks_like_consult_followup(user_text)
    return False

async def resolve_consult_card(
    user_id: str,
    message_card: dict | None = None,
    memory_state: dict | None = None,
    from_product: bool | None = None,
) -> dict | None:

    normalized = normalize_consult_card(message_card)
    if normalized:
        return normalized
    if from_product is False:
        return None

    from app.services.redis_service import redis_service

    cached = await redis_service.get_consult_product(user_id)
    normalized = normalize_consult_card(cached)
    if normalized:
        return normalized

    if memory_state:
        normalized = normalize_consult_card(memory_state.get("consultProduct"))
        if normalized:
            return normalized
    return None
