package com.simlect.controller.internal;

import com.simlect.api.dto.LessStockPageDTO;
import com.simlect.api.dto.ProductIdDTO;
import com.simlect.api.dto.ProductIdListDTO;
import com.simlect.api.dto.ProductSalesIncreaseDTO;
import com.simlect.api.dto.ProductSnapshotBatchVO;
import com.simlect.api.vo.ProductRagIndexVO;
import com.simlect.api.vo.ProductSearchIndexVO;
import com.simlect.api.vo.ProductSkuSnapshotVO;
import com.simlect.biz.ProductInternalService;
import com.simlect.biz.ProductSkuService;
import com.simlect.controller.ABaseController;
import com.simlect.entity.vo.PaginationResultVO;
import com.simlect.api.vo.ProductSkuListVO;
import com.simlect.entity.vo.ResponseVO;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/product")
public class ProductInternalController extends ABaseController {

    @Resource
    private ProductInternalService productInternalService;

    @Resource
    private ProductSkuService productSkuService;

    @PostMapping("/snapshotBatch")
    public ResponseVO<ProductSnapshotBatchVO> snapshotBatch(@Valid @RequestBody ProductIdListDTO dto) {
        return getSuccessResponseVO(productInternalService.snapshotBatch(
                dto == null ? null : dto.getProductIds()));
    }

    @PostMapping("/defaultSku")
    public ResponseVO<ProductSkuSnapshotVO> defaultSku(@Valid @RequestBody ProductIdDTO dto) {
        return getSuccessResponseVO(productInternalService.defaultSku(dto.getProductId()));
    }

    @PostMapping("/increaseSales")
    public ResponseVO<Void> increaseSales(@Valid @RequestBody ProductSalesIncreaseDTO dto) {
        if (dto != null) {
            productInternalService.increaseSales(dto.getProductId(),
                    dto.getQty() == null ? 0 : dto.getQty());
        }
        return getSuccessResponseVO(null);
    }

    @PostMapping("/searchIndex")
    public ResponseVO<ProductSearchIndexVO> getSearchIndex(@Valid @RequestBody ProductIdDTO dto) {
        return getSuccessResponseVO(productInternalService.getSearchIndex(dto.getProductId()));
    }

    @PostMapping("/ragIndex")
    public ResponseVO<ProductRagIndexVO> getRagIndex(@Valid @RequestBody ProductIdDTO dto) {
        return getSuccessResponseVO(productInternalService.getRagIndex(dto.getProductId()));
    }

    @PostMapping("/lessStockSkuPage")
    public ResponseVO<PaginationResultVO<ProductSkuListVO>> lessStockSkuPage(@RequestBody LessStockPageDTO dto) {
        if (dto == null) {
            dto = new LessStockPageDTO();
        }
        return getSuccessResponseVO(productSkuService.lessStockSkuPage(
                dto.getPageNo(), dto.getPageSize(), dto.getThreshold()));
    }

    @PostMapping("/listOnSaleProductIds")
    public ResponseVO<List<String>> listOnSaleProductIds() {
        return getSuccessResponseVO(productInternalService.listOnSaleProductIds());
    }
}
