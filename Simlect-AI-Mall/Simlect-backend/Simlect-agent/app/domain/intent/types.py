from enum import Enum

class IntentKind(str, Enum):
    PRODUCT_CONSULT = "PRODUCT_CONSULT"
    PRODUCT_SEARCH = "PRODUCT_SEARCH"
    QUERY_ORDER = "QUERY_ORDER"
    REFUND = "REFUND"
    CANCEL_ORDER = "CANCEL_ORDER"
    CONFIRM_RECEIPT = "CONFIRM_RECEIPT"
    QUERY_LOGISTICS = "QUERY_LOGISTICS"
    QUERY_COUPON = "QUERY_COUPON"
    PRODUCT_REVIEW = "PRODUCT_REVIEW"
    RECOMMENT = "RECOMMENT"
    QUERY_COMMENT = "QUERY_COMMENT"
    CHAT = "CHAT"

INTENT_PROMPT_KEY: dict[IntentKind, str] = {
    IntentKind.PRODUCT_CONSULT: "product_consult",
    IntentKind.PRODUCT_SEARCH: "product_search",
    IntentKind.QUERY_ORDER: "query_order",
    IntentKind.REFUND: "refund",
    IntentKind.CANCEL_ORDER: "cancel_order",
    IntentKind.CONFIRM_RECEIPT: "confirm_receipt",
    IntentKind.QUERY_LOGISTICS: "query_logistics",
    IntentKind.QUERY_COUPON: "query_coupon",
    IntentKind.PRODUCT_REVIEW: "product_review",
    IntentKind.RECOMMENT: "recomment",
    IntentKind.QUERY_COMMENT: "query_comment",
    IntentKind.CHAT: "chat",
}
