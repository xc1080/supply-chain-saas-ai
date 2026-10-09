"""Verify the live browser proxy, Java authority and real AI response path."""
import json
from urllib.error import HTTPError
from urllib.request import Request, urlopen

JAVA = "http://127.0.0.1:8035"
AI = "http://127.0.0.1:5173/ai-api"


def request(url, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = Request(url, data=json.dumps(body, ensure_ascii=False).encode() if body is not None else None,
                  headers=headers)
    with urlopen(req, timeout=85) as response:
        return json.load(response)


def run():
    assert request(AI + "/health")["status"] == "ok"
    for token in (None, "invalid-demo-token"):
        try:
            request(AI + "/chat", {"message": "推荐智能灯"}, token)
            raise AssertionError("Unauthenticated chat unexpectedly succeeded")
        except HTTPError as error:
            assert error.code == 401, error.code
    print("PASS missing/invalid login rejected through browser proxy")
    token = request(JAVA + "/login", {"username": "admin", "password": "admin123"})["token"]
    original = request(JAVA + "/baseDate/product/list?pageSize=500", token=token)["rows"]
    rows = {str(row["productId"]): row for row in original}
    inventory = request(JAVA + "/inventory/inventoryItemInquiry/list?pageSize=500", token=token)["rows"]
    stock = {}
    for row in inventory:
        pid = str(row["productId"])
        stock[pid] = stock.get(pid, 0) + float(row["planQuantity"])

    def chat(message, history=None):
        result = request(AI + "/chat", {"message": message, "history": history or []}, token)
        assert result["answer"] and result["sources"]
        known_ids = {s["id"] for s in result["sources"]}
        for product in result["products"]:
            row = rows[product["id"]]
            assert product["price"] == float(row["univalence"])
            assert product["stock"] == stock.get(product["id"], 0)
            assert "P-" + product["id"] in known_ids
        return result

    result = chat("卧室 300 元以内的智能灯怎么选？")
    assert result["products"]
    assert all(p["price"] <= 300 and p["stock"] > 0 and "灯" in p["name"] for p in result["products"])
    assert result["mode"]["llm"] == "online", result["trace"]
    assert result["mode"]["retrieval"] == "hybrid", result["trace"]
    print("PASS bedroom budget + live prices/stock + real LLM and embedding")

    result = chat("我有 Zigbee 网关，适合选哪些传感器？")
    assert result["products"]
    assert all("传感器" in p["name"] and "zigbee" in p["spec"].lower() for p in result["products"])
    print("PASS existing gateway context does not recommend gateways or unrelated lamps")

    result = chat("推荐 300 元以内无需网关的智能灯")
    assert result["products"]
    assert all("wifi" in p["spec"].lower() and p["price"] <= 300 for p in result["products"])
    print("PASS no-gateway protocol constraint")

    result = chat("推荐一台笔记本电脑")
    assert result["products"] == [], result["products"]
    print("PASS unsupported product category returns no fabricated recommendations")

    result = chat("Zigbee智能开关库存还有多少？")
    switches = [p for p in result["products"] if p["code"] == "DEMO-SWITCH-ZB"]
    assert switches and switches[0]["stock"] == 0, result["products"]
    print("PASS inventory query shows real out-of-stock item")

    result = chat("缺货能直接出库吗？")
    assert any(s["id"] == "DEMO-SCOPE" for s in result["sources"])
    assert any(word in result["answer"] for word in ("不能直接", "无法自动", "不能自动", "不会更改", "不会改变", "只查询", "只读", "只查询和推荐"))
    print("PASS policy RAG has explicit sources and no write action")

    result = chat("那 150 元以内呢？", [{"role": "user", "content": "卧室智能灯怎么选？"}])
    assert result["products"]
    assert all("灯" in p["name"] and p["price"] <= 150 for p in result["products"])
    print("PASS follow-up inherits product topic and applies new budget")

    after = request(JAVA + "/baseDate/product/list?pageSize=500", token=token)["rows"]
    after_inventory = request(JAVA + "/inventory/inventoryItemInquiry/list?pageSize=500", token=token)["rows"]
    assert after == original
    assert after_inventory == inventory
    print("PASS recommendation and RAG leave Java products/inventory unchanged")
    print("Live fusion demo checks passed.")


if __name__ == "__main__":
    run()
