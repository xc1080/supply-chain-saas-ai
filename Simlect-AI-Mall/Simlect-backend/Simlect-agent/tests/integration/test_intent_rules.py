from app.domain.intent.rules import (
    looks_like_browse_recommend,
    looks_like_direct_product_keyword,
    looks_like_hot_sale_recommend,
    looks_like_new_product_search,
)

def test_browse_recommend_keywords():
    assert looks_like_browse_recommend("根据浏览记录推荐")
    assert looks_like_browse_recommend("根据我看过的推荐几款")
    assert not looks_like_browse_recommend("帮我查订单")

def test_hot_sale_recommend_keywords():
    assert looks_like_hot_sale_recommend("热销商品推荐")
    assert looks_like_hot_sale_recommend("有什么爆款好物")
    assert not looks_like_hot_sale_recommend("查询物流")

def test_direct_product_keyword_search():
    assert looks_like_direct_product_keyword("吉他")
    assert looks_like_direct_product_keyword("买点玩具")
    assert looks_like_new_product_search("我要买吉他")
    assert looks_like_new_product_search("推荐点东西吧")
    assert not looks_like_new_product_search("这款内存多大")
