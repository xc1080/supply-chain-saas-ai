package com.simlect.controller.internal;

import com.simlect.api.dto.CartDeleteBatchDTO;
import com.simlect.biz.CartInternalService;
import com.simlect.controller.ABaseController;
import com.simlect.entity.vo.ResponseVO;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/cart")
public class CartInternalController extends ABaseController {

    @Resource
    private CartInternalService cartInternalService;

    @PostMapping("/deleteBatch")
    public ResponseVO<Void> deleteBatch(@RequestBody CartDeleteBatchDTO dto) {
        cartInternalService.deleteBatch(dto);
        return getSuccessResponseVO(null);
    }
}
