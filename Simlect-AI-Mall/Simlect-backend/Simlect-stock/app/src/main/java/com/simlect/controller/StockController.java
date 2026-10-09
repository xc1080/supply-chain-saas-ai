package com.simlect.controller;

import com.simlect.api.vo.ServiceHealthVO;
import com.simlect.entity.vo.ResponseVO;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/stock")
public class StockController extends ABaseController {

    @PostMapping("/health")
    public ResponseVO<ServiceHealthVO> health() {
        return getSuccessResponseVO(new ServiceHealthVO("simlect-stock", "UP"));
    }
}
