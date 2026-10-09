package com.simlect.controller.internal;

import com.simlect.api.dto.LessStockPageDTO;
import com.simlect.api.dto.ProductIdDTO;
import com.simlect.api.dto.SkuStockBatchChangeDTO;
import com.simlect.api.dto.SkuStockChangeDTO;
import com.simlect.api.dto.SkuStockDTO;
import com.simlect.api.dto.SkuStockQueryDTO;
import com.simlect.api.dto.SkuStockSetDTO;
import com.simlect.api.vo.ProductTotalStockVO;
import com.simlect.api.vo.StockChangeResultVO;
import com.simlect.biz.SkuStockService;
import com.simlect.controller.ABaseController;
import com.simlect.entity.vo.PaginationResultVO;
import com.simlect.entity.vo.ResponseVO;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/stock")
public class StockInternalController extends ABaseController {

    @Resource
    private SkuStockService skuStockService;

    @PostMapping("/get")
    public ResponseVO<SkuStockDTO> getStock(@Valid @RequestBody SkuStockQueryDTO dto) {
        return getSuccessResponseVO(skuStockService.getStock(dto.getProductId(), dto.getPropertyValueIdHash()));
    }

    @PostMapping("/change")
    public ResponseVO<StockChangeResultVO> changeStock(@Valid @RequestBody SkuStockChangeDTO dto) {
        return getSuccessResponseVO(new StockChangeResultVO(skuStockService.changeStock(dto)));
    }

    @PostMapping("/changeBatch")
    public ResponseVO<StockChangeResultVO> changeStockBatch(@Valid @RequestBody SkuStockBatchChangeDTO dto) {
        return getSuccessResponseVO(new StockChangeResultVO(skuStockService.changeStockBatch(dto)));
    }

    @PostMapping("/lockAndVerify")
    public ResponseVO<Void> lockAndVerify(@Valid @RequestBody SkuStockBatchChangeDTO dto) {
        skuStockService.lockAndVerify(dto);
        return getSuccessResponseVO(null);
    }

    @PostMapping("/set")
    public ResponseVO<Void> setStock(@Valid @RequestBody SkuStockSetDTO dto) {
        skuStockService.setStock(dto.getProductId(), dto.getPropertyValueIdHash(), dto.getStock());
        return getSuccessResponseVO(null);
    }

    @PostMapping("/totalByProduct")
    public ResponseVO<ProductTotalStockVO> totalByProduct(@Valid @RequestBody ProductIdDTO dto) {
        return getSuccessResponseVO(new ProductTotalStockVO(dto.getProductId(),
                skuStockService.totalByProductId(dto.getProductId())));
    }

    @PostMapping("/listLessThan")
    public ResponseVO<PaginationResultVO<SkuStockDTO>> listLessThan(@RequestBody LessStockPageDTO dto) {
        if (dto == null) {
            dto = new LessStockPageDTO();
        }
        return getSuccessResponseVO(skuStockService.listLessThan(dto.getPageNo(), dto.getPageSize(), dto.getThreshold()));
    }
}
