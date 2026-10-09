package com.simlect.controller.admin;

import com.simlect.api.support.OrderFeignSupport;
import com.simlect.api.support.SearchToolFeignSupport;
import com.simlect.entity.vo.ResponseVO;
import com.simlect.biz.StatisticsInfoService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequestMapping("/admin/tool")
@RestController
@Slf4j
public class ToolController extends com.simlect.controller.admin.ABaseController {

    @Resource
    private StatisticsInfoService statisticsInfoService;
    @Resource
    private SearchToolFeignSupport searchToolFeignSupport;
    @Resource
    private OrderFeignSupport orderFeignSupport;

    @PostMapping("/statistics")
    public ResponseVO statistics() {
        statisticsInfoService.statistics(null, null);
        return getSuccessResponseVO(null);
    }

    @PostMapping("/productData")
    public ResponseVO productData() {
        searchToolFeignSupport.productData();
        return getSuccessResponseVO(null);
    }

    @PostMapping("/ragData")
    public ResponseVO ragData() {
        searchToolFeignSupport.ragData();
        return getSuccessResponseVO(null);
    }

    @PostMapping("/addAllOrderToDelayQueue")
    public ResponseVO addAllOrderToDelayQueue() {
        orderFeignSupport.addAllWaitPayToDelayQueue();
        return getSuccessResponseVO(null);
    }
}
