package com.simlect.service.impl;

import com.simlect.constants.ReliableMessageSender;
import com.simlect.utils.JsonUtils;
import com.simlect.entity.enums.MessageReliabilityLevelEnum;
import com.simlect.entity.enums.OutboxMessageStatusEnum;
import com.simlect.entity.po.LocalMessageOutbox;
import com.simlect.mappers.LocalMessageOutboxMapper;
import com.simlect.service.OutboxMessageService;
import com.simlect.utils.StringTools;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

@Service
@Slf4j
public class OutboxMessageServiceImpl implements OutboxMessageService {

    @Resource
    private LocalMessageOutboxMapper localMessageOutboxMapper;
    @Lazy
    @Resource
    private ReliableMessageSender reliableMessageSender;

    @Override
    public Long savePending(String exchange, String routingKey, Object payload,
                            String idempotencyKey, MessageReliabilityLevelEnum reliabilityLevel) {
        if (StringTools.isEmpty(idempotencyKey) || StringTools.isEmpty(exchange) || StringTools.isEmpty(routingKey)) {
            throw new IllegalArgumentException("outbox 参数不完整");
        }
        LocalMessageOutbox existing = localMessageOutboxMapper.selectByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            return existing.getId();
        }
        Date now = new Date();
        LocalMessageOutbox row = new LocalMessageOutbox();
        row.setIdempotencyKey(idempotencyKey);
        row.setExchangeName(exchange);
        row.setRoutingKey(routingKey);
        row.setPayloadJson(JsonUtils.toJson(payload));
        row.setReliabilityLevel(reliabilityLevel == null
                ? MessageReliabilityLevelEnum.STANDARD.getCode()
                : reliabilityLevel.getCode());
        row.setStatus(OutboxMessageStatusEnum.PENDING.getStatus());
        row.setRetryCount(0);
        row.setCreateTime(now);
        row.setUpdateTime(now);
        try {
            localMessageOutboxMapper.insert(row);
            return row.getId();
        } catch (DuplicateKeyException dup) {
            LocalMessageOutbox again = localMessageOutboxMapper.selectByIdempotencyKey(idempotencyKey);
            return again == null ? null : again.getId();
        }
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void tryDispatch(Long id) {
        if (id == null) {
            return;
        }
        LocalMessageOutbox row = localMessageOutboxMapper.selectById(id);
        if (row == null) {
            return;
        }
        Integer status = row.getStatus();
        if (OutboxMessageStatusEnum.SENT.getStatus().equals(status)
                || OutboxMessageStatusEnum.SENDING.getStatus().equals(status)) {
            return;
        }
        Integer from = status;
        Integer claimed = localMessageOutboxMapper.updateStatus(
                id, from, OutboxMessageStatusEnum.SENDING.getStatus(), null, null, false);
        if (claimed == null || claimed != 1) {
            return;
        }
        try {
            Object payload = JsonUtils.parse(row.getPayloadJson());
            MessageReliabilityLevelEnum level = MessageReliabilityLevelEnum.STANDARD;
            if (MessageReliabilityLevelEnum.HIGH.getCode().equalsIgnoreCase(row.getReliabilityLevel())) {
                level = MessageReliabilityLevelEnum.HIGH;
            }
            // 重放路径：同步 Confirm，避免 HIGH 异步假成功
            reliableMessageSender.replaySend(
                    row.getExchangeName(), row.getRoutingKey(), payload, row.getIdempotencyKey());
            localMessageOutboxMapper.updateStatus(
                    id, OutboxMessageStatusEnum.SENDING.getStatus(),
                    OutboxMessageStatusEnum.SENT.getStatus(), null, new Date(), false);
        } catch (Exception e) {
            log.error("Outbox 投递失败 id={}, key={}", id, row.getIdempotencyKey(), e);
            localMessageOutboxMapper.updateStatus(
                    id, OutboxMessageStatusEnum.SENDING.getStatus(),
                    OutboxMessageStatusEnum.FAILED.getStatus(),
                    truncate(e.getMessage()), null, true);
        }
    }

    @Override
    public int dispatchPendingBatch(int batchSize, int maxRetries) {
        if (batchSize <= 0) {
            batchSize = 20;
        }
        // 避开刚写入、即将由 afterCommit 处理的记录
        Date before = new Date(System.currentTimeMillis() - 3000L);
        List<LocalMessageOutbox> list = localMessageOutboxMapper.selectDispatchBatch(
                Arrays.asList(
                        OutboxMessageStatusEnum.PENDING.getStatus(),
                        OutboxMessageStatusEnum.FAILED.getStatus()),
                before,
                batchSize);
        if (list == null || list.isEmpty()) {
            return 0;
        }
        int ok = 0;
        for (LocalMessageOutbox row : list) {
            int retry = row.getRetryCount() == null ? 0 : row.getRetryCount();
            if (retry >= maxRetries) {
                continue;
            }
            try {
                tryDispatch(row.getId());
                LocalMessageOutbox after = localMessageOutboxMapper.selectById(row.getId());
                if (after != null && OutboxMessageStatusEnum.SENT.getStatus().equals(after.getStatus())) {
                    ok++;
                }
            } catch (Exception e) {
                log.warn("Outbox 批量投递异常 id={}", row.getId(), e);
            }
        }
        return ok;
    }

    private static String truncate(String msg) {
        if (msg == null) {
            return null;
        }
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }
}
