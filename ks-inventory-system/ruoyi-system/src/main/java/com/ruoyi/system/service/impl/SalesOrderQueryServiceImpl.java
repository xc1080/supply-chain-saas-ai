package com.ruoyi.system.service.impl;

import com.ruoyi.common.core.domain.entity.DetailOrderForm;
import com.ruoyi.common.core.domain.entity.HeadOrderForm;
import com.ruoyi.system.mapper.DetailOrderFormMapper;
import com.ruoyi.system.mapper.HeadOrderFormMapper;
import com.ruoyi.system.service.SalesOrderQueryService;
import com.ruoyi.system.service.CommerceProcurementService;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;

/**
 * 销售订单查询 业务层处理
 *
 * @author KrityCat
 */
@Service
public class SalesOrderQueryServiceImpl implements SalesOrderQueryService {

    @Autowired(required = false)
    private CommerceProcurementService procurement;

    @Autowired
    private HeadOrderFormMapper headOrderFormMapper;

    @Autowired
    private DetailOrderFormMapper detailOrderFormMapper;

    /**
     * @param bo 订单信息
     * @return 销售订单头单查询
     */
    @Override
    public List<HeadOrderForm> salesHeadOrderFormQuery(HeadOrderForm bo) {
        return headOrderFormMapper.headOrderFormQuery(bo);
    }

    /**
     * @param bo 订单信息
     * @return 销售订单明细单查询
     */
    @Override
    public List<DetailOrderForm> salesDetailOrderFormQuery(DetailOrderForm bo) {
        return detailOrderFormMapper.selectDetailOrderFormQuery(bo);
    }

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
