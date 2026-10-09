package com.ruoyi.system.service;

/** Business authority is independent of the HTTP verb and RuoYi menu permissions. */
public enum CommerceCapability {
    READ, CATALOG, FULFILMENT, REFUND_REVIEW, REFUND_EXECUTE,
    STOCK_ADJUST, SUPPLY_POLICY, SUPPLY_DRAFT, SUPPLY_REVIEW, SHOP_MEMBERS
}
