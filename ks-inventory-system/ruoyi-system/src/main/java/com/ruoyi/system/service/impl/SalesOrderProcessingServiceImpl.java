package com.ruoyi.system.service.impl;

import com.ruoyi.common.core.domain.entity.DetailOrderForm;
import com.ruoyi.common.core.domain.entity.HeadOrderForm;
import com.ruoyi.common.core.domain.entity.OrderFrom;
import com.ruoyi.system.mapper.DetailOrderFormMapper;
import com.ruoyi.system.mapper.HeadOrderFormMapper;
import com.ruoyi.system.service.SalesOrderProcessingService;
import com.ruoyi.system.service.CommerceProcurementService;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/**
 * 销售订单制作 业务层处理
 *
 * @author KrityCat
 */
@Service
public class SalesOrderProcessingServiceImpl implements SalesOrderProcessingService {

    @Autowired(required = false)
    private CommerceProcurementService procurement;

    @Autowired
    private HeadOrderFormMapper headOrderFormMapper;

    @Autowired
    private DetailOrderFormMapper detailOrderFormMapper;

    /**
     * @param systematicOrderForm 系统单号
     * @return 订单详情
     */
    @Override
    public HeadOrderForm selectSalesOrderFormById(String systematicOrderForm) {
        return headOrderFormMapper.selectHeadOrderFormById(systematicOrderForm);
    }

    /**
     * @param bo 订单信息
     * @return 保存销售订单信息
     */
    @Override
    @Transactional
    public int saveSalesOrderForm(OrderFrom bo) {
        if (procurement != null) {
            if (bo == null || !Long.valueOf(2).equals(bo.getOrderFormType())) throw new ServiceException("销售入口只能保存销售订单",400);
            procurement.validateNonPurchaseOrders(Collections.singletonList(bo.getSystematicOrderForm()));
            if (bo.getDetails() == null || bo.getDetails().isEmpty() || bo.getDetails().size() > 100)
                throw new ServiceException("销售订单需包含1至100条明细",400);
            for (DetailOrderForm row : bo.getDetails()) {
                if (row == null || (row.getSystematicOrderForm() != null && !bo.getSystematicOrderForm().equals(row.getSystematicOrderForm())))
                    throw new ServiceException("销售订单明细不能使用其他订单编号",400);
                row.setSystematicOrderForm(bo.getSystematicOrderForm());
            }
        }
        long count = headOrderFormMapper.selectHeadOrderFormByCount(bo).size();
        List<DetailOrderForm> detail = bo.getDetails();
        if (count == 0) {
            detailOrderFormMapper.addDetailOrderForm(detail);
            headOrderFormMapper.addHeadOrderForm(bo);
        } else {
            detailOrderFormMapper.delDetailOrderForm(detail);
            detailOrderFormMapper.addDetailOrderForm(detail);
            headOrderFormMapper.updateHeadOrderForm(bo);
        }
        return 1;
    }

    /**
     * @param bo 订单信息
     * @return 删除销售订单信息
     */
    @Override
    @Transactional
    public int delSalesOrder(List<DetailOrderForm> bo) {
        if (procurement != null) {
            if (bo == null || bo.isEmpty()) throw new ServiceException("请选择销售订单",400);
            List<String> ids=new ArrayList<>();for(DetailOrderForm detail:bo)ids.add(detail.getSystematicOrderForm());
            procurement.validateNonPurchaseOrders(ids);
        }
        detailOrderFormMapper.delDetailOrderForm(bo);
        return headOrderFormMapper.delHeadOrderForm(bo);
    }
}
