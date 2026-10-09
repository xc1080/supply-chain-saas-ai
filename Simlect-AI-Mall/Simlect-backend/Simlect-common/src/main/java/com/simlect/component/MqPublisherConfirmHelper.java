package com.simlect.component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
public class MqPublisherConfirmHelper implements RabbitTemplate.ConfirmCallback, RabbitTemplate.ReturnsCallback {

    @Value("${mq.publisher.confirm-timeout-ms:5000}")
    private long defaultConfirmTimeoutMs;

    @Value("#{${mq.publisher.confirm-timeout-by-exchange:{}}}")
    private Map<String, Long> confirmTimeoutByExchange = Collections.emptyMap();

    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();

    @Resource
    private RabbitTemplate rabbitTemplate;

    @PostConstruct
    public void attachCallbacks() {
        rabbitTemplate.setConfirmCallback(this);
        rabbitTemplate.setReturnsCallback(this);
        rabbitTemplate.setMandatory(true);
        logPublisherConfirmConfig();
    }

    private void logPublisherConfirmConfig() {
        if (rabbitTemplate.getConnectionFactory() instanceof CachingConnectionFactory factory) {
            if (!factory.isPublisherConfirms()) {
                log.error("RabbitMQ 未启用 Publisher Confirm（spring.rabbitmq.publisher-confirm-type），"
                        + "Confirm 回调不会触发，所有发送将超时失败");
            } else {
                log.info("RabbitMQ Publisher Confirm 已启用, defaultTimeoutMs={}, exchangeOverrides={}",
                        defaultConfirmTimeoutMs, confirmTimeoutByExchange);
            }
        }
    }

    private long resolveConfirmTimeoutMs(String exchange) {
        if (exchange != null && confirmTimeoutByExchange.containsKey(exchange)) {
            return confirmTimeoutByExchange.get(exchange);
        }
        return defaultConfirmTimeoutMs;
    }

    @Override
    public void confirm(CorrelationData correlationData, boolean ack, String cause) {
        if (correlationData == null || correlationData.getId() == null) {
            return;
        }
        CompletableFuture<Boolean> future = pending.remove(correlationData.getId());
        if (future != null) {
            if (!ack) {
                log.warn("Publisher confirm NACK, id={}, cause={}", correlationData.getId(), cause);
            }
            future.complete(ack);
        }
    }

    @Override
    public void returnedMessage(org.springframework.amqp.core.ReturnedMessage returned) {
        String messageId = returned.getMessage().getMessageProperties().getMessageId();
        if (messageId == null) {
            return;
        }
        CompletableFuture<Boolean> future = pending.remove(messageId);
        if (future != null) {
            log.warn("Publisher return unroutable, id={}, reply={}", messageId, returned.getReplyText());
            future.complete(false);
        }
    }

    public void sendAndAwaitConfirm(String exchange, String routingKey, Object body,
                                    MessagePostProcessor processor, String correlationId) throws Exception {
        long timeoutMs = resolveConfirmTimeoutMs(exchange);
        CorrelationData correlationData = new CorrelationData(correlationId);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pending.put(correlationId, future);
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, body, processor, correlationData);
            Boolean ack = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (!Boolean.TRUE.equals(ack)) {
                throw new org.springframework.amqp.AmqpException("Broker 未确认消息持久化, key=" + correlationId);
            }
        } catch (TimeoutException e) {
            pending.remove(correlationId);
            throw new org.springframework.amqp.AmqpException(
                    "Publisher confirm 超时, key=" + correlationId + ", timeoutMs=" + timeoutMs, e);
        } finally {
            pending.remove(correlationId);
        }
    }
}
