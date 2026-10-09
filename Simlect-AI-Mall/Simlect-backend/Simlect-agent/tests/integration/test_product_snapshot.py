"""商品快照字段规范化测试。"""

import json

from app.services.product_snapshot_service import ProductSnapshotService

def test_normalize_snapshot_keys_snake_to_camel():
    svc = ProductSnapshotService()
    raw = {
        "product_id": "p1",
        "product_name": "测试商品",
        "min_price": 99.0,
        "total_stock": 10,
        "in_stock": True,
    }
    normalized = svc._normalize_snapshot_keys(raw)
    assert normalized["productId"] == "p1"
    assert normalized["productName"] == "测试商品"
    assert normalized["minPrice"] == 99.0
    assert normalized["totalStock"] == 10
    assert normalized["inStock"] is True
    dumped = json.loads(json.dumps(normalized))
    assert "product_id" not in dumped
