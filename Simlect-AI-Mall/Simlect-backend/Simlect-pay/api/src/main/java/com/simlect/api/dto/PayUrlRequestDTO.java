package com.simlect.api.dto;

import java.io.Serializable;
import java.math.BigDecimal;

public class PayUrlRequestDTO implements Serializable {
    private String payChannel;
    private String payOrderId;
    private String subject;
    private BigDecimal amount;

    public PayUrlRequestDTO() {
    }

    public PayUrlRequestDTO(String payChannel, String payOrderId, String subject, BigDecimal amount) {
        this.payChannel = payChannel;
        this.payOrderId = payOrderId;
        this.subject = subject;
        this.amount = amount;
    }

    public String getPayChannel() {
        return payChannel;
    }

    public void setPayChannel(String payChannel) {
        this.payChannel = payChannel;
    }

    public String getPayOrderId() {
        return payOrderId;
    }

    public void setPayOrderId(String payOrderId) {
        this.payOrderId = payOrderId;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
