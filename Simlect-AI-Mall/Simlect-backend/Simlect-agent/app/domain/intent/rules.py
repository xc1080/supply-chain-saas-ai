import re

_PHONE_HINTS = ("手机", "iphone", "苹果", "三星", "华为", "小米", "oppo", "vivo", "荣耀")
_SNACK_HINTS = ("零食", "小吃", "坚果", "糖果", "饼干")
_COMPUTER_HINTS = ("电脑", "台式", "台式机", "笔记本", "平板", "主机", "显示器")
_TOY_HINTS = ("玩具", "玩偶", "模型", "积木", "乐高")
_MUSIC_HINTS = ("吉他", "乐器", "钢琴", "尤克里里", "电子琴")
_OTHER_PRODUCT_HINTS = ("家电", "服饰", "衣服", "鞋子", "美妆", "护肤", "儿童")
_ALL_PRODUCT_HINTS = (
    _PHONE_HINTS + _SNACK_HINTS + _COMPUTER_HINTS + _TOY_HINTS + _MUSIC_HINTS + _OTHER_PRODUCT_HINTS
)
_SWITCH_HINTS = ("转", "切换", "换品类", "换个", "不要这款", "不要这个", "看看别的", "别的品类", "跨品类")
_SEARCH_VERBS = ("搜索", "找", "推荐", "买", "想要", "需要", "看看", "有没有")
_CONSULT_FOLLOWUP_HINTS = (
    "这款",
    "这个",
    "规格",
    "接口",
    "尺寸",
    "内存",
    "硬盘",
    "价格",
    "库存",
    "颜色",
    "怎么样",
    "介绍",
    "配置",
    "单主机",
    "显示器",
    "版本",
)

def looks_like_hot_sale_recommend(user_text: str) -> bool:

    if not user_text:
        return False
    t = user_text.strip()
    return any(k in t for k in ("热销", "热卖", "爆款")) or (
        "推荐" in t and any(k in t for k in ("商品", "好物"))
    )

def looks_like_browse_recommend(user_text: str) -> bool:

    if not user_text:
        return False
    t = user_text.strip()
    return any(k in t for k in ("浏览", "看过", "足迹")) or (
        "根据" in t and "推荐" in t
    )

def _text_lower(text: str) -> str:
    return (text or "").strip().lower()

def _mentions_any(text: str, hints: tuple[str, ...]) -> bool:
    t = _text_lower(text)
    return any(h in t for h in hints)

def _consult_product_category(consult_name: str | None) -> str | None:

    name = _text_lower(consult_name or "")
    if not name:
        return None
    if any(h in name for h in _PHONE_HINTS) and "电脑" not in name and "笔记本" not in name:
        return "phone"
    if any(h in name for h in _SNACK_HINTS):
        return "snack"
    if any(h in name for h in _COMPUTER_HINTS):
        return "computer"
    return "other"

def looks_like_category_switch(user_text: str, consult_product_name: str | None = None) -> bool:

    t = (user_text or "").strip()
    if not t:
        return False
    tl = t.lower()
    if any(h in t for h in _SWITCH_HINTS):
        return True

    if "怎么选" in t or "哪个好" in t or "对比" in t:
        if _mentions_any(t, _PHONE_HINTS) or _mentions_any(t, _COMPUTER_HINTS):
            consult_cat = _consult_product_category(consult_product_name)
            if consult_cat == "computer" and _mentions_any(t, _PHONE_HINTS):
                return True
            if consult_cat == "phone" and _mentions_any(t, _COMPUTER_HINTS):
                return True
            if consult_cat and consult_cat not in ("phone", "computer"):
                return True

    if re.search(r"预算\s*\d+", t) and any(v in t for v in ("买", "购", "想要", "推荐")):
        return True

    consult_cat = _consult_product_category(consult_product_name)
    if consult_cat == "computer":
        if _mentions_any(t, _PHONE_HINTS) and not _mentions_any(t, _COMPUTER_HINTS):
            return True
        if _mentions_any(t, _SNACK_HINTS):
            return True
    if consult_cat == "phone" and _mentions_any(t, _SNACK_HINTS):
        return True
    if _mentions_any(t, _TOY_HINTS + _MUSIC_HINTS):
        consult_name_lower = _text_lower(consult_product_name or "")
        if not any(h in consult_name_lower for h in _TOY_HINTS + _MUSIC_HINTS):
            return True

    if len(t) <= 12:
        if _mentions_any(t, _PHONE_HINTS) and consult_cat == "computer":
            return True
        if _mentions_any(t, _SNACK_HINTS) and consult_cat != "snack":
            return True
        if _mentions_any(t, _TOY_HINTS + _MUSIC_HINTS):
            return True
    return False

def looks_like_direct_product_keyword(user_text: str) -> bool:

    t = (user_text or "").strip()
    if not t or len(t) > 16:
        return False
    # Order-history phrases contain 「买」 but are not product search
    if any(
        k in t
        for k in (
            "买了什么",
            "买过什么",
            "最近买",
            "最近购买",
            "我的订单",
            "最近的订单",
            "最近订单",
        )
    ):
        return False
    if _mentions_any(t, _ALL_PRODUCT_HINTS):
        return True
    # 买点零食/买个手机 — classifier required; exclude 买了什么
    if re.search(r"买(点|些|个|一款)[\u4e00-\u9fff]{1,8}", t):
        return True
    return False

def looks_like_new_product_search(user_text: str) -> bool:

    t = (user_text or "").strip()
    if not t:
        return False
    from app.utils.order_ids import extract_order_id, extract_order_item_id

    if extract_order_item_id(t) or extract_order_id(t):
        return False
    if any(
        k in t
        for k in (
            "买了什么",
            "买过什么",
            "最近买",
            "最近购买",
            "我的订单",
            "最近的订单",
            "最近订单",
            "查订单",
        )
    ):
        return False
    if looks_like_direct_product_keyword(t):
        return True
    if any(v in t for v in _SEARCH_VERBS) and (
        _mentions_any(t, _ALL_PRODUCT_HINTS)
        or re.search(r"预算\s*\d+", t)
    ):
        return True

    if "推荐" in t and len(t) <= 40 and (
        _mentions_any(t, _PHONE_HINTS + _COMPUTER_HINTS + _TOY_HINTS + _MUSIC_HINTS)
        or "好物" in t
        or "商品" in t
        or "东西" in t
    ):
        return True
    return False

def looks_like_consult_followup(user_text: str) -> bool:

    t = (user_text or "").strip()
    if not t:
        return False
    if any(h in t for h in _CONSULT_FOLLOWUP_HINTS):
        return True
    if len(t) <= 20 and not any(v in t for v in _SEARCH_VERBS):
        return True
    return False
