from app.harness.guardrails.product_text_guard import text_promises_product_cards

def test_text_promises_product_cards():
    assert text_promises_product_cards("为您搜索了吉他类商品，请查看下方推荐卡片。")
    assert text_promises_product_cards("请看以下推荐结果")
    assert not text_promises_product_cards("这款吉他音色均衡，适合初学者。")
