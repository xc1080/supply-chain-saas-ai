"""Server-only adapter to Java's transactional commerce authority.

SQLite retains visitor sessions, carts and chat. Orders, reservations, payment
events and dispatch live in MySQL; an unavailable authority never falls back to
creating a disconnected local order.
"""
from __future__ import annotations

import hashlib
import hmac
import os
import re
import time
import uuid
from urllib.parse import quote, urlparse

import httpx
from fastapi import HTTPException
from product_media import product_cover


def owner_id(session: str) -> str:
    return hashlib.sha256(session.encode()).hexdigest()


def store_order(order: dict) -> dict:
    order_id = str(order["orderId"])
    items = [{"orderItemId": order_id + ":" + str(item["productId"]),
              "productId": str(item["productId"]), "productName": item["productName"],
              "productCode": item["productCode"], "cover": product_cover(item["productCode"], item.get("cover")),
              "propertyInfo": item["spec"], "itemAmount": float(item["unitPrice"]),
              "buyCount": int(item.get("orderedQuantity", item["quantity"])), "remark": "",
              **{key: item.get(key) for key in (
                  "orderedQuantity", "shippedQuantity", "returnedQuantity", "cancelledQuantity", "refundedQuantity",
                  "unshippedQuantity", "shippableQuantity", "afterSalesAvailableQuantity", "unshippedRefundAvailableQuantity", "returnAvailableQuantity")},
              "orderItemStatus": int(order["orderStatus"])} for item in order["items"]]
    total = float(order["totalAmount"])
    shipping = order.get("shippingAddress") or {}
    return {**{key: order.get(key) for key in (
                "activityId", "tenantId", "shopId", "expiresAt", "closeReason", "statusName", "paymentProvider", "paidTime", "shippedTime", "receivedTime",
                "transactionId", "receiptId", "carrier", "trackingNo", "paymentOutcome", "afterSalesId", "afterSalesStatus", "refundedAmount",
                "fulfillmentStatus", "shippedAmount", "returnedAmount", "shipments", "afterSales", "afterSalesCases",
                "dispatchPromise", "paymentOperation", "refundOperation")},
            "orderId": order_id, "payOrderId": order_id,
            "orderTime": order["createTime"], "createTime": order["createTime"],
            "orderStatus": int(order["orderStatus"]), "amount": total,
            "originalAmount": total, "totalAmount": total, "goodsAmount": total, "payAmount": total,
            "orderItemList": items, "shippingAddress": order.get("shippingAddress"),
            **{key: shipping.get(key, "") for key in ("addressId", "addressee", "phone", "address")},
            "afterSale": order.get("afterSale"), "availableActions": order.get("availableActions"),
            "demo": True, "legacy": False}


class CommerceAPI:
    def __init__(self, service, token_reader, *, token_refresher=None):
        self.service = service
        self.token_reader = token_reader
        self.token_refresher = token_refresher
        self.shop_id = os.getenv("FUSION_SHOP_ID", "default")
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,32}", self.shop_id):
            raise ValueError("FUSION_SHOP_ID must be a valid configured shop identifier")
        self._client: httpx.AsyncClient | None = None

    async def start(self):
        if self._client is None or self._client.is_closed:
            local = urlparse(self.service.JAVA_URL).hostname in {"127.0.0.1", "localhost", "::1"}
            # Reuse connections and cap outstanding authority calls. Keep TLS verification enabled.
            # Local loopback bypasses unrelated proxy environment settings; remote deployments retain them.
            self._client = httpx.AsyncClient(timeout=self.service.TIMEOUT, trust_env=not local,
                                             limits=httpx.Limits(max_connections=64, max_keepalive_connections=32,
                                                                 keepalive_expiry=30))

    async def close(self):
        client, self._client = self._client, None
        if client is not None: await client.aclose()

    async def get_client(self):
        await self.start()
        return self._client

    async def request(self, method, path, *, params=None, body=None):
        try:
            tenant = os.getenv("FUSION_TENANT", "demo")
            secret = os.getenv("FUSION_CUSTOMER_ASSERTION_SECRET", "")
            if len(secret) < 32:
                raise HTTPException(503, "客户身份委托未配置")
            customer = (body or {}).get("ownerId", (params or {}).get("ownerId", ""))
            client = await self.get_client()
            token = await self.token_reader()
            for attempt in range(2):
                # Only an explicit authentication rejection is safe to resend.
                # Timeouts, connection failures and business 403s never retry here.
                stamp, nonce = str(int(time.time())), uuid.uuid4().hex
                payload = "\n".join((tenant, self.shop_id, method.upper(), path, str(customer), stamp, nonce))
                proof = hmac.new(secret.encode(), payload.encode(), hashlib.sha256).hexdigest()
                response = await client.request(method, self.service.JAVA_URL + path,
                                            headers={"Authorization": token, "X-Shop-ID": self.shop_id,
                                                     "X-Customer-Owner": str(customer), "X-Customer-Timestamp": stamp,
                                                     "X-Customer-Nonce": nonce, "X-Customer-Signature": proof},
                                            params=params, json=body)
                if response.status_code == 401:
                    data, code = {}, 401
                else:
                    data = response.json()
                    code = int(data.get("code", response.status_code))
                if code == 401 and attempt == 0 and self.token_refresher is not None:
                    token = await self.token_refresher(token)
                    continue
                break
            if code == 401:
                raise HTTPException(503, '商城业务账号认证失效，请联系商家')
            if code != 200:
                if code in (400, 403, 404, 409, 422, 429, 503):
                    raise HTTPException(code, str(data.get("msg") or "订单操作未完成"))
                raise HTTPException(502, "订单业务服务暂不可用，请稍后重试")
            response.raise_for_status()
            return data["data"]
        except HTTPException:
            raise
        except (httpx.HTTPError, ValueError, KeyError, TypeError):
            raise HTTPException(502, "无法连接订单业务服务，请稍后重试；相同请求不会重复下单") from None

    async def inventory(self):
        return await self.request("GET", "/commerce/catalog")

    async def orders(self, session):
        orders, page_number = [], 1
        while True:
            result = await self.request("GET", "/commerce/orders", params={
                "ownerId": owner_id(session), "pageNum": page_number, "pageSize": 100})
            rows = result["rows"]
            orders.extend(store_order(order) for order in rows)
            if not rows or len(orders) >= result["total"]:
                return orders
            page_number += 1
            if page_number > 100:
                raise HTTPException(502, "订单数量超过当前演示查询上限")

    async def detail(self, session, order_id):
        return store_order(await self.request("GET", "/commerce/orders/" + quote(order_id, safe=""),
                                              params={"ownerId": owner_id(session)}))

    async def create(self, session, key, quantities, shipping_address):
        return store_order(await self.request("POST", "/commerce/orders", body={
            "ownerId": owner_id(session), "requestKey": key, "shippingAddress": shipping_address,
            "items": [{"productId": pid, "quantity": qty} for pid, qty in sorted(quantities.items())]}))

    async def action(self, session, order_id, action, **extra):
        return store_order(await self.request("POST", "/commerce/orders/" + quote(order_id, safe="") + "/" + action,
                                              body={"ownerId": owner_id(session), **extra}))

    async def query_payment(self, session, order_id, operation_id):
        path = "/commerce/orders/" + quote(order_id, safe="") + "/payments/" + quote(operation_id, safe="") + "/query"
        return store_order(await self.request("POST", path, body={"ownerId": owner_id(session)}))
