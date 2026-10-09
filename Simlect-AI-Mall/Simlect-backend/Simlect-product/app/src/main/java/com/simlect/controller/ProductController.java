package com.simlect.controller;

import com.simlect.constants.RabbitMQConfig;
import com.simlect.constants.ReliableMessageSender;
import com.simlect.api.dto.BrowseHistoryMessageDTO;
import com.simlect.entity.enums.MessageReliabilityLevelEnum;
import com.simlect.entity.po.ProductInfo;
import com.simlect.entity.query.ProductInfoQuery;
import com.simlect.entity.query.SimplePage;
import com.simlect.entity.query.SysCategoryQuery;
import com.simlect.entity.vo.Product4VO;
import com.simlect.entity.vo.ResponseVO;
import com.simlect.entity.dto.TokenUserInfoDTO;
import com.simlect.exception.BusinessException;
import com.simlect.biz.ProductInfoService;
import com.simlect.biz.SysCategoryService;
import com.simlect.support.MqIdempotencyKeys;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/product")
@Validated
public class ProductController extends ABaseController {

    @Resource
    private SysCategoryService sysCategoryService;

    @Resource
    private ProductInfoService productInfoService;

    @Resource
    private ReliableMessageSender reliableMessageSender;

    @GetMapping("/loadCategory")
    public ResponseVO loadCategory() {
        return getSuccessResponseVO(sysCategoryService.findListByParam(new SysCategoryQuery()));
    }

    @GetMapping("/loadCommendProduct")
    public ResponseVO loadCommendProduct() {
        ProductInfoQuery query = new ProductInfoQuery();
        query.setCommendType(1);
        query.setStatus(1);
        query.setOrderBy("create_time desc");
        query.setSimplePage(new SimplePage(0, 11));
        return getSuccessResponseVO(productInfoService.findListByPage(query).getList());
    }

    @PostMapping("/loadProduct")
    public ResponseVO loadProduct(@NotNull Integer pageNo,
                                  String categoryId,
                                  BigDecimal priceFrom,
                                  BigDecimal priceTo,
                                  String sortField,
                                  String sortType) {
        ProductInfoQuery query = new ProductInfoQuery();
        query.setPageNo(pageNo);
        query.setStatus(com.simlect.api.enums.ProductStatusEnum.ON_SALE.getStatus());
        query.setPriceFrom(priceFrom);
        query.setPriceTo(priceTo);
        query.setOrderBy(buildProductOrderBy(sortField, sortType));
        if (categoryId == null) {
            query.setCommendType(0);
            return getSuccessResponseVO(productInfoService.findListByPage(query));
        }
        query.setCategoryIdOrPCategoryId(categoryId);
        return getSuccessResponseVO(productInfoService.findListByPage(query));
    }

    private String buildProductOrderBy(String sortField, String sortType) {
        if ("price".equals(sortField)) {
            return "asc".equalsIgnoreCase(sortType) ? "min_price asc, total_sale desc" : "min_price desc, total_sale desc";
        }
        if ("sale".equals(sortField)) {
            return "total_sale desc, min_price asc";
        }
        return "commend_type desc, total_sale desc, create_time desc";
    }

    @PostMapping("/getProduct")
    public ResponseVO getProduct(@NotNull String productId) {
        Product4VO product = productInfoService.getProduct4VOByProductId(productId);
        TokenUserInfoDTO tokenUserInfo = getTokenUserInfo();
        if (tokenUserInfo != null && tokenUserInfo.getUserId() != null) {
            BrowseHistoryMessageDTO message = new BrowseHistoryMessageDTO();
            message.setUserId(tokenUserInfo.getUserId());
            message.setProductId(productId);
            message.setBrowseTime(System.currentTimeMillis());
            reliableMessageSender.sendMessage(
                    RabbitMQConfig.BROWSE_EXCHANGE,
                    RabbitMQConfig.BROWSE_RECORD_KEY,
                    message,
                    MqIdempotencyKeys.browseRecord(tokenUserInfo.getUserId(), productId),
                    MessageReliabilityLevelEnum.HIGH);
        }
        return getSuccessResponseVO(product);
    }

    @PostMapping("/search")
    public ResponseVO search(@NotEmpty String keyWords,
                             BigDecimal priceFrom,
                             BigDecimal priceTo,
                             String sortType,
                             String sortField,
                             Integer pageNo) {
        throw new BusinessException("请使用搜索服务接口 /api/search 完成商品搜索");
    }
}
