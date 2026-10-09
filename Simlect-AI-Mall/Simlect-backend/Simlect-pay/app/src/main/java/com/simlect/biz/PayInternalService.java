package com.simlect.biz;

import com.simlect.api.dto.PayCloseDTO;
import com.simlect.api.dto.PayQueryDTO;
import com.simlect.api.dto.PayRefundDTO;
import com.simlect.api.dto.PayTradeCreateDTO;
import com.simlect.api.dto.PayTradeStatusDTO;
import com.simlect.api.dto.PayUrlRequestDTO;
import com.simlect.component.SpringContext;
import com.simlect.api.dto.PayInfoDTO;
import com.simlect.api.dto.PayOrderNotifyDTO;
import com.simlect.api.enums.PayChannelEnum;
import com.simlect.exception.BusinessException;
import com.simlect.utils.StringTools;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

@Service
public class PayInternalService {

    @Resource
    private PayTradeRecordService payTradeRecordService;

    public void createPending(PayTradeCreateDTO dto) {
        payTradeRecordService.createPending(
                dto.getUserId(), dto.getPayOrderId(), dto.getOrderId(), dto.getPayAmount(), dto.getPayChannel());
    }

    public void markSuccess(PayTradeStatusDTO dto) {
        payTradeRecordService.markSuccess(dto.getPayOrderId(), dto.getChannelOrderId());
    }

    public void markClosed(PayTradeStatusDTO dto) {
        payTradeRecordService.markClosed(dto.getPayOrderId());
    }

    public void markRefunded(PayTradeStatusDTO dto) {
        payTradeRecordService.markRefunded(dto.getPayOrderId());
    }

    public PayInfoDTO getPayUrl(PayUrlRequestDTO dto) {
        PayChannel channel = resolveChannel(dto.getPayChannel());
        PayChannelEnum channelEnum = PayChannelEnum.resolve(dto.getPayChannel());
        return channel.getPayUrl(channelEnum, dto.getPayOrderId(), dto.getSubject(), dto.getAmount());
    }

    public void refund(PayRefundDTO dto) {
        resolveChannel(dto.getPayChannel()).refund(
                dto.getSourcePayOrderId(), dto.getRefundOrderId(), dto.getRefundAmount());
    }

    public void closeOrder(PayCloseDTO dto) {
        resolveChannel(dto.getPayChannel()).closeOrder(dto.getPayOrderId());
    }

    public PayOrderNotifyDTO queryOrder(PayQueryDTO dto) {
        return resolveChannel(dto.getPayChannel()).queryOrder(dto.getPayOrderId());
    }

    private PayChannel resolveChannel(String payChannel) {
        if (StringTools.isEmpty(payChannel)) {
            throw new BusinessException("支付渠道为空");
        }
        PayChannelEnum channelEnum = PayChannelEnum.resolve(payChannel);
        if (channelEnum == null) {
            throw new BusinessException("不支持的支付渠道");
        }
        return (PayChannel) SpringContext.getBean(channelEnum.getBeanName());
    }
}
