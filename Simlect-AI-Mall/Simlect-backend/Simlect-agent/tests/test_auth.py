from fastapi.testclient import TestClient

from app.main import app

def test_load_history_without_token_returns_901_json():
    client = TestClient(app)
    resp = client.post("/api/agent/loadHistoryMessage")
    assert resp.status_code == 200
    body = resp.json()
    assert body["code"] == 901
    assert body["status"] == "error"
    assert "登录超时" in (body.get("info") or "")
