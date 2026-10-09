package com.simlect.constants;

public final class InternalApiHeaders {

    public static final String INTERNAL_TOKEN = "X-Internal-Token";
    public static final String TRACE_ID = "X-Trace-Id";
    public static final String TRACE_ID_MDC = "traceId";

    public static final String REMOTE_COMPENSATE_EXCHANGE = "REMOTE_COMPENSATE";
    public static final String REMOTE_STOCK_CHANGE_BATCH = "stock.changeBatch";
    public static final String REMOTE_COUPON_UNLOCK = "coupon.unlock";

    private InternalApiHeaders() {
    }
}
