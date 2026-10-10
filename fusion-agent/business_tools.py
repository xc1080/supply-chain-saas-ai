"""Code-owned tool contracts. The model chooses tools; business APIs own authority."""
from __future__ import annotations
import hashlib
import json
import os
import re
from fastapi import HTTPException

TOOL_CATALOG = {
    name: {"name": name, "version": 1, "effect": "READ", "inputSchema": {"type": "object", "additionalProperties": False},
           "channel": "any", "capability": "CUSTOMER_OR_READ", "timeoutSeconds": 12}
    for name in ("search_products", "check_stock", "check_budget", "check_compatibility", "read_orders", "plan_bundle", "finish")
}
TOOL_CATALOG["plan_replenishment"] = {"name": "plan_replenishment", "version": 1, "effect": "READ",
    "inputSchema": {"type": "object", "additionalProperties": False}, "channel": "workspace",
    "capability": "SUPPLY_DRAFT", "timeoutSeconds": 12}
for name, capability in (("read_order_stock", "READ"), ("read_order_settlement", "FINANCE_REVIEW_OR_EXECUTE")):
    TOOL_CATALOG[name] = {"name": name, "version": 1, "effect": "READ", "channel": "workspace", "capability": capability,
        "inputSchema": {"type": "object", "required": ["orderId"], "additionalProperties": False,
                        "properties": {"orderId": {"type": "string", "pattern": "^SC[A-Z0-9]{1,30}$"}}}, "timeoutSeconds": 12}

def tool_enabled(name: str) -> bool:
    disabled = {part.strip() for part in os.getenv("FUSION_DISABLED_TOOLS", "").split(",") if part.strip()}
    return name in TOOL_CATALOG and name not in disabled

def require_tool(name: str, channel: str | None) -> dict:
    if not tool_enabled(name): raise HTTPException(503, "业务工具已停用")
    spec = TOOL_CATALOG[name]
    if spec["channel"] == "workspace" and channel != "workspace": raise HTTPException(403, "经营工具仅供已授权商家使用")
    return spec

def order_review_id(message: str) -> str | None:
    if not any(word in message for word in ("经营", "费用", "结算", "利润", "库存流转", "库存变化", "成本")): return None
    found = re.findall(r"(?<![A-Za-z0-9])SC[A-Z0-9]{1,30}(?![A-Za-z0-9])", message)
    if len(set(found)) > 1: raise HTTPException(422, "请每次选择一张订单进行经营分析")
    return found[0] if found else None

def safe_stock(raw: dict, order_id: str) -> dict:
    if not isinstance(raw, dict) or raw.get("orderId") != order_id: raise HTTPException(502, "库存证据不属于当前订单")
    result = {"orderId": order_id, "writes": False}
    for key, fields in {
        "products": ("productId", "productCode", "productName", "spec", "quantity"),
        "warehouseEvents": ("eventKey", "operation", "receiptId", "productId", "warehouseId", "deltaQuantity", "beforeQuantity", "afterQuantity"),
        "holds": ("productId", "quantity", "status"),
    }.items():
        rows = raw.get(key)
        if not isinstance(rows, list) or len(rows) > 2000 or not all(isinstance(row, dict) for row in rows): raise HTTPException(502, "库存证据格式错误")
        result[key] = [{field: row.get(field) for field in fields} for row in rows]
    return result

def safe_settlement(raw: dict, order_id: str) -> dict:
    if not isinstance(raw, dict) or raw.get("orderId") != order_id: raise HTTPException(502, "结算证据不属于当前订单")
    fields = ("orderId", "currency", "provider", "externalChannel", "grossPaid", "refundedAmount", "netReceipts", "netStockCost",
              "costComplete", "moneyComplete", "approvedExpenseAmount", "settledExpenseAmount", "outstandingExpenseAmount", "operatingResult",
              "provisionalContribution", "resultStatus")
    result = {field: raw.get(field) for field in fields}
    rows = raw.get("expenses")
    if not isinstance(rows, list) or len(rows) > 2000 or not all(isinstance(row, dict) for row in rows): raise HTTPException(502, "费用证据格式错误")
    result["expenses"] = [{key: row.get(key) for key in ("expenseId", "category", "amount", "status", "settledAmount", "outstandingAmount")} for row in rows]
    return result

def review_plan(order_id: str, stock: dict, settlement: dict) -> dict:
    actions = []
    if any(row["status"] == "DRAFT" for row in settlement["expenses"]):
        actions.append({"kind": "REVIEW_EXPENSE_EVIDENCE", "requiresApproval": True, "executionAllowed": False})
    if float(settlement.get("outstandingExpenseAmount") or 0) > 0:
        actions.append({"kind": "PREPARE_EXPENSE_SETTLEMENT", "requiresApproval": True, "executionAllowed": False})
    if settlement.get("costComplete") is not True:
        actions.append({"kind": "VERIFY_MISSING_COST_EVIDENCE", "requiresApproval": True, "executionAllowed": False})
    if settlement.get("moneyComplete") is not True:
        actions.append({"kind": "VERIFY_PAYMENT_EVIDENCE", "requiresApproval": True, "executionAllowed": False})
    facts = {"stock": stock, "settlement": settlement}
    return {"type": "ORDER_REVIEW", "status": "READY_FOR_REVIEW", "orderId": order_id,
            "factsVersion": hashlib.sha256(json.dumps(facts, ensure_ascii=False, sort_keys=True).encode()).hexdigest(),
            "facts": facts, "actions": actions, "writes": False, "autonomousExecution": False}

def review_answer(plan: dict) -> str:
    money = plan["facts"]["settlement"]
    lines = [f"订单 {plan['orderId']} 的经营核验：",
             f"已收 {money['grossPaid']} 元，已退 {money['refundedAmount']} 元，净收款 {money['netReceipts']} 元。",
             f"已审核费用 {money['approvedExpenseAmount']} 元，其中已结算 {money['settledExpenseAmount']} 元，待结算 {money['outstandingExpenseAmount']} 元。"]
    if money.get("costComplete") is not True or money.get("moneyComplete") is not True:
        lines.append("成本或资金证据尚未完整，当前不能确认经营结果。")
    elif money.get("operatingResult") is not None:
        lines.append(f"按原出入库成本快照核算，经营结果为 {money['operatingResult']} 元。")
    else:
        lines.append(f"订单仍在履约或售后处理中，暂估经营贡献 {money.get('provisionalContribution')} 元。")
    lines.append(f"库存变更可追溯到 {len(plan['facts']['stock']['warehouseEvents'])} 条仓库凭证。")
    if plan["actions"]: lines.append("建议先核对待审费用及未结余额，再由有权限的人员确认执行。")
    lines.append("本次只查询并形成建议，未修改库存或执行付款；资金来源为本地沙箱。[B-ORDER]")
    return "\n".join(lines)
