"""商品正文与卡片同步测试。"""

from app.harness.guardrails.product_text_guard import (
    build_consult_product_cards_json,
    collect_known_product_names,
    should_force_product_cards,
    text_contains_product_info,
)
from app.utils.biz_payload import build_product_search_message, parse_product_search_message

def test_text_contains_product_listing():
    text = (
        "旺旺雪饼厚烧海苔385g—12元，经典零食\n"
        "名创优品小猪B-BO趴姿公仔—33.9元，可爱毛绒抱枕"
    )
    assert text_contains_product_info(text)

def test_text_contains_consult_product_name():
    consult = {"productName": "雅马哈FG800沙暴渐变-41英寸原声款", "productId": "1"}
    text = "这款雅马哈FG800沙暴渐变音色很好，1749.0元"
    known = collect_known_product_names(None, consult, None)
    assert text_contains_product_info(text, known)

def test_should_force_when_plain_text_lists_products():
    text = "推荐：可乐—3元、雪碧—3.5元"
    assert should_force_product_cards(text, text, None, None, None)

def test_should_not_force_when_already_json():
    cards = '[{"productId":"1","productName":"可乐"}]'
    raw = build_product_search_message("请看下方", cards)
    assert not should_force_product_cards("可乐 3元", raw, None, None, None)

def test_should_not_force_on_product_consult_turn():
    consult = {"productId": "1", "productName": "雅马哈FG800", "minPrice": 1749}
    text = "这款雅马哈FG800音色很好，1749.0元"
    assert not should_force_product_cards(
        text, text, None, consult, None, is_consult_turn=True
    )

    consult = {
        "productId": "101",
        "productName": "FG800",
        "cover": "a.jpg",
        "minPrice": 1749,
    }
    raw = build_consult_product_cards_json(consult)
    assert raw
    _, products = parse_product_search_message(
        build_product_search_message("介绍如下", raw or "[]")
    )
    assert len(products or []) == 1
    assert products[0]["productId"] == "101"
