"""SKU cart/order boundary with isolated sessions and a fake business authority."""
import copy
import json
import unittest

import test_store_api as fixtures
from test_sku_catalog import metadata


class SkuAuthority(fixtures.FakeCommerceAuthority):
    def stock(self, pid):
        return {**super().stock(pid), "skuCatalog": self.sku_meta(pid)}

    def sku_meta(self, pid):
        return copy.deepcopy(next(p for p in self.catalog if p["id"] == pid).get("skuCatalog"))

    def handle(self, request):
        if request.url.path == "/commerce/orders" and request.method == "POST":
            body = json.loads(request.content)
            for item in body["items"]:
                meta = self.sku_meta(item["productId"])
                expected = meta["propertyValueIds"] if meta else "default"
                if item.get("propertyValueIds") not in (None, "", expected):
                    return self.response(code=409, msg="所选规格与货品不一致")
            response = super().handle(request)
            data = response.json()
            if data["code"] == 200:
                order = self.orders[data["data"]["orderId"]]
                for item in order["items"]:
                    item.setdefault("skuSnapshot", self.sku_meta(item["productId"]))
                return self.response(order)
            return response
        return super().handle(request)


class StoreSkuTests(unittest.TestCase):
    login = fixtures.StoreIntegrationTests.login
    api = fixtures.StoreIntegrationTests.api
    data = fixtures.StoreIntegrationTests.data
    cart = fixtures.StoreIntegrationTests.cart
    post_order = fixtures.StoreIntegrationTests.post_order
    create_order = fixtures.StoreIntegrationTests.create_order
    detail = fixtures.StoreIntegrationTests.detail
    tearDown = fixtures.StoreIntegrationTests.tearDown

    def setUp(self):
        fixtures.StoreIntegrationTests.setUp(self)
        white = self.service.catalog[0]
        white.update(skuCatalog=metadata(), code="LAB-SKU-WHITE", name="学习台灯")
        black = copy.deepcopy(white)
        black.update(id="black", code="LAB-SKU-BLACK", spec="Zigbee/12W", price=159, stock=2,
                     skuCatalog=metadata("black", "zigbee"))
        self.service.catalog.append(black)
        self.authority = SkuAuthority(self.service.catalog)

    def test_detail_uses_actual_variant_ids_and_independent_price_stock(self):
        detail = self.data(self.alice, "product/getProduct", productId="black")
        self.assertEqual(detail["productInfo"]["productId"], "black")
        self.assertEqual({row["skuId"] for row in detail["skuList"]}, {"lamp", "black"})
        black = next(row for row in detail["skuList"] if row["skuId"] == "black")
        self.assertEqual((black["price"], black["stock"], black["propertyValueIds"]), (159, 2, "black-zigbee"))

    def test_cart_preserves_selected_sku_and_rejects_other_combination(self):
        self.data(self.alice, "productCart/add2Cart", productId="black", buyCount=1, propertyValueIds="black-zigbee")
        self.assertEqual(self.cart(self.alice)[0]["productId"], "black")
        self.assertEqual(self.cart(self.alice)[0]["propertyValueIds"], "black-zigbee")
        self.assertEqual(self.cart(self.alice)[0]["price"], 159)
        rejected = self.api(self.alice, "productCart/add2Cart", productId="black", buyCount=1, propertyValueIds="white-wifi")
        self.assertEqual(rejected["code"], 422)
        self.assertEqual(self.cart(self.alice)[0]["buyCount"], 1)

    def test_order_authority_receives_selection_and_holds_only_exact_sku(self):
        oid = self.create_order(self.alice, lines=[{"productId": "black", "buyCount": 2, "propertyValueIds": "black-zigbee", "price": .01}])
        detail = self.detail(self.alice, oid)
        self.assertEqual(detail["amount"], 318)
        self.assertEqual(detail["orderItemList"][0]["productId"], "black")
        self.assertEqual(detail["orderItemList"][0]["skuSnapshot"]["attributes"], {"color": "black", "protocol": "zigbee"})
        self.assertEqual(self.authority.held, {"black": 2})
        wrong = self.post_order(self.bob, key="bad-combo", lines=[{"productId": "black", "buyCount": 1, "propertyValueIds": "white-wifi"}])
        self.assertEqual(wrong["code"], 409)

    def test_historical_snapshot_survives_new_catalog_label_and_drops_internal_fields(self):
        self.service.catalog[0]["skuCatalog"]["costPrice"] = 17
        oid = self.create_order(self.alice, lines=[{"productId": "lamp", "buyCount": 1, "propertyValueIds": "white-wifi"}])
        self.service.catalog[0]["skuCatalog"]["properties"][0]["propertyValues"][0]["propertyValue"] = "后改显示名称"
        item = self.detail(self.alice, oid)["orderItemList"][0]
        self.assertEqual(item["skuSnapshot"]["properties"][0]["propertyValues"][0]["propertyValue"], "白色")
        self.assertNotIn("costPrice", item["skuSnapshot"])

    def test_conflicting_duplicate_sku_selections_never_reach_business_authority(self):
        result = self.post_order(self.alice, key="conflict", lines=[
            {"productId": "lamp", "buyCount": 1, "propertyValueIds": "white-wifi"},
            {"productId": "lamp", "buyCount": 1, "propertyValueIds": "black-zigbee"}])
        self.assertEqual(result["code"], 422)
        self.assertEqual(self.authority.orders, {})


if __name__ == "__main__": unittest.main()
