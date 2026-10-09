from app.constants import (
    CONFIRM_RECEIPT_ORDER_STATUSES,
    REFUNDABLE_ORDER_STATUSES,
    REVIEWABLE_ORDER_STATUSES,
    ORDER_STATUS_COMPLETED,
    ORDER_STATUS_NAMES,
)
from app.utils.biz_payload import ORDER_STATUS_NAMES as BIZ_NAMES

def test_order_status_names_java_parity():
    assert ORDER_STATUS_NAMES[7] == "部分退款"
    assert ORDER_STATUS_NAMES[6] == "已退款,交易关闭"
    assert BIZ_NAMES is ORDER_STATUS_NAMES

def test_confirm_receipt_allowed_status():
    assert CONFIRM_RECEIPT_ORDER_STATUSES == {2, 7}

def test_refund_allowed_status():
    assert REFUNDABLE_ORDER_STATUSES == {1, 2, 7}

def test_review_allowed_status():
    assert REVIEWABLE_ORDER_STATUSES == {3, 7}
