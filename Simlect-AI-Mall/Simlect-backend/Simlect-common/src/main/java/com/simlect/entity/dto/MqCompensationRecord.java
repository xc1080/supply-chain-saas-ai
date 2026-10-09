package com.simlect.entity.dto;

import com.simlect.entity.enums.MessageReliabilityLevelEnum;
import lombok.Data;

import java.io.Serializable;

@Data
public class MqCompensationRecord implements Serializable {

    private String idempotencyKey;
    private String exchange;
    private String routingKey;

    private Object payload;
    private MessageReliabilityLevelEnum reliabilityLevel;
    private long failedAt;
    private int retryCount;
    private String errorMessage;
}
