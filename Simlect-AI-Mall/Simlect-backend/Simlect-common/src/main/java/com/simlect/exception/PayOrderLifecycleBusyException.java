package com.simlect.exception;

public class PayOrderLifecycleBusyException extends BusinessException {

    public PayOrderLifecycleBusyException() {
        super("支付订单处理中，请稍后重试");
    }
}
