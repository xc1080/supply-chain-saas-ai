"""Exercise live C inventory reservations, real RAG and owned cancellation.

Only one DEMO item is reserved. Cleanup cancels the unpaid order on failure;
this smoke never pays, dispatches, or permanently reduces book inventory.
"""
import asyncio
import json
import uuid
from contextlib import AsyncExitStack

import httpx
import websockets

BASE = "http://127.0.0.1:6001"


def stock_snapshot(products):
    return {p["productId"]: (p["bookStock"], p["reservedStock"], p["stock"]) for p in products}


async def run():
    async with httpx.AsyncClient(base_url=BASE, timeout=80) as client, httpx.AsyncClient(base_url=BASE, timeout=30) as stranger, AsyncExitStack() as cleanup:
        async def call(path, payload=None, owner=client):
            response = await (owner.get("/api/" + path) if payload is None else owner.post("/api/" + path, json=payload))
            response.raise_for_status()
            body = response.json()
            assert body["code"] == 200, (path, body.get("info"))
            return body["data"]

        async def inventory():
            return stock_snapshot((await call("product/loadProduct", {}))["list"])

        async def cancel_if_unpaid(order_id):
            detail = await call("order/getMyOrderDetail", {"orderId": order_id})
            if detail["orderStatus"] == 0:
                await call("order/cancelOrder", {"orderId": order_id})
                assert (await inventory())[pid] == before[pid], "Cleanup failed to release the smoke reservation"
                print("CLEANUP cancelled unpaid smoke order and released its reservation", flush=True)

        await call("account/autoLogin")
        await call("account/autoLogin", owner=stranger)
        original = (await call("product/loadProduct", {}))["list"]
        assert len(original) == 8
        for product in original:
            response = await client.get(product["cover"])
            assert response.status_code == 200 and "<svg" in response.text
            assert product["availableStock"] == product["stock"]
            assert product["stock"] == product["bookStock"] - product["reservedStock"]
        print("PASS 8 current Java products, available/book/reserved stock and local images through C frontend proxy", flush=True)
        product = next(p for p in original if p["productCode"] == "DEMO-LAMP-WIFI")
        assert product["stock"] >= 1, "DEMO-LAMP-WIFI needs one available unit for this smoke"
        pid = product["productId"]
        before = stock_snapshot(original)
        book, reserved, available = before[pid]
        expected_hold = (book, reserved + 1, available - 1)
        await call("productCart/add2Cart", {"productId": pid, "propertyValueIds": "default", "buyCount": 1})
        assert (await call("productCart/loadProductCart", {}))["list"][0]["buyCount"] == 1
        assert (await call("productCart/loadProductCart", {}, stranger))["totalCount"] == 0
        order_body = {"payMethod": "demo", "addressId": "demo-address", "orderFrom": "0", "clientRequestId": str(uuid.uuid4()), "orderList": [{"productId": pid, "propertyValueIds": "default", "buyCount": 1, "price": 0.01}]}
        order = await call("order/postOrder", order_body)
        cleanup.push_async_callback(cancel_if_unpaid, order["orderId"])
        assert order == await call("order/postOrder", order_body)
        detail = await call("order/getMyOrderDetail", {"orderId": order["orderId"]})
        assert detail["amount"] == product["price"] and detail["orderStatus"] == 0
        assert detail["legacy"] is False and detail["paymentProvider"] == "LOCAL_SANDBOX"
        held = await inventory()
        assert held[pid] == expected_hold, (before[pid], held[pid])
        assert all(held[key] == value for key, value in before.items() if key != pid)
        assert (await call("productCart/loadProductCart", {}))["totalCount"] == 0
        assert (await call("order/loadMyOrder", {}, stranger))["totalCount"] == 0
        hidden = (await stranger.post("/api/order/getMyOrderDetail", json={"orderId": order["orderId"]})).json()
        assert hidden["code"] == 404
        print("PASS server pricing, idempotent reservation, unpaid state, cart checkout and session isolation", flush=True)

        cookie = "; ".join(f"{k}={v}" for k, v in client.cookies.items())
        async with websockets.connect("ws://127.0.0.1:6001/ws/", additional_headers={"Cookie": cookie}, origin=BASE, open_timeout=20) as socket:
            await socket.send("ping")
            assert await socket.recv() == "pong"

            async def ask(question):
                ack = await call("agent/sendMessage", {"message": question})
                packet = json.loads(await asyncio.wait_for(socket.recv(), 85))
                assert packet["messageId"] == ack["messageId"] and packet["outPutType"] == 1, packet
                return json.loads(packet["assistantMessage"])

            answer = await ask("卧室用，预算200元，没有网关，推荐一款灯，为什么？")
            assert answer["type"] == "PRODUCT_SEARCH_RESULT", answer
            assert answer["mode"]["llm"] == "online" and answer["mode"]["retrieval"] == "hybrid", answer
            intro = answer["intro"]
            assert "{{" not in intro and "planQuantity" not in intro and "[DEMO-" not in intro
            assert any(p["productCode"] == "DEMO-LAMP-WIFI" for p in answer["products"])
            assert any(s["id"] == "DEMO-PROTOCOL" for s in answer["sources"])
            assert answer["citations"] and set(answer["citations"]) <= {s["id"] for s in answer["sources"]}
            print("PASS real model with hybrid retrieval and grounded product advice: " + intro.replace("\n", " "), flush=True)
            answer = await ask("我的订单里买了什么，现在是什么状态，总额多少？")
            assert answer["type"] == "CHAT_RESULT" and answer["mode"]["llm"] == "online", answer
            assert product["productName"] in answer["answer"] and f"{product['price']:g}" in answer["answer"] and "{{" not in answer["answer"]
            assert any(word in answer["answer"] for word in ("待支付", "待付款")) and "预留" in answer["answer"], answer
            assert "O-" + order["orderId"] in answer["citations"], answer
            print("PASS own order facts with real model: " + answer["answer"].replace("\n", " "), flush=True)
            proposal = await ask("取消订单" + order["orderId"])
            assert proposal["type"] == "ACTION_CONFIRM" and proposal["status"] == 0
            assert (await call("order/getMyOrderDetail", {"orderId": order["orderId"]}))["orderStatus"] == 0
            assert (await inventory())[pid] == expected_hold
            denied = (await stranger.post("/api/agent/confirmAction", json={"actionToken": proposal["token"]})).json()
            assert denied["code"] == 404
            assert (await inventory())[pid] == expected_hold
            await call("agent/confirmAction", {"actionToken": proposal["token"]})
            await call("agent/confirmAction", {"actionToken": proposal["token"]})
            assert (await call("order/getMyOrderDetail", {"orderId": order["orderId"]}))["orderStatus"] == 4
            assert (await inventory())[pid] == before[pid]
            print("PASS cancellation waits for owned confirmation, releases reservation once, and safely replays", flush=True)
        history = await call("agent/loadHistoryMessage", {})
        assert len(history["list"]) == 3 and all(m["status"] == 2 for m in history["list"])
        assert json.loads(history["list"][0]["assistantMessage"])["status"] == 1
        assert (await call("agent/loadHistoryMessage", {}, stranger))["totalCount"] == 0
        assert await inventory() == before
        print("PASS persisted confirmation status, private history, released holds and unchanged Java book inventory", flush=True)


if __name__ == "__main__":
    asyncio.run(run())
