package com.ruoyi.system.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/** Read-only carrier boundary. Observations cannot apply delivery or stock transitions.
 * Real adapters must keep the supplied tenant/shop/subject scope, and enforce their own
 * connection/read deadlines. A timeout is an unavailable observation, never a receipt. */
public interface CommerceLogisticsProvider {
    String name();
    String source();
    Observation query(Subject subject) throws TimeoutException;

    final class Subject {
        public final String tenantId,shopId,type,id,carrierCode,trackingNo;
        public Subject(String tenantId,String shopId,String type,String id,String carrierCode,String trackingNo) {
            this.tenantId=tenantId;this.shopId=shopId;this.type=type;this.id=id;
            this.carrierCode=carrierCode;this.trackingNo=trackingNo;
        }
    }
    final class Observation {
        public final String observedAt;
        public final List<Map<String,Object>> events;
        public Observation(String observedAt,List<Map<String,Object>> events){this.observedAt=observedAt;this.events=events;}
    }
}
