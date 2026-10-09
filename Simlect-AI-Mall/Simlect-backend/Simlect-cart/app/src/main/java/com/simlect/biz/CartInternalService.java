package com.simlect.biz;

import com.simlect.api.dto.CartDeleteBatchDTO;
import com.simlect.api.dto.CartDeleteItemDTO;
import com.simlect.entity.po.ProductCart;
import com.simlect.entity.query.ProductCartQuery;
import com.simlect.mappers.ProductCartMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

@Service
public class CartInternalService {

    @Resource
    private ProductCartMapper<ProductCart, ProductCartQuery> productCartMapper;

    public void deleteBatch(CartDeleteBatchDTO dto) {
        if (dto == null || CollectionUtils.isEmpty(dto.getItems())) {
            return;
        }
        List<ProductCart> list = new ArrayList<>();
        for (CartDeleteItemDTO item : dto.getItems()) {
            if (item == null) {
                continue;
            }
            ProductCart cart = new ProductCart();
            cart.setUserId(item.getUserId());
            cart.setProductId(item.getProductId());
            cart.setPropertyValueIdHash(item.getPropertyValueIdHash());
            cart.setPropertyValueIds(item.getPropertyValueIds());
            list.add(cart);
        }
        if (!list.isEmpty()) {
            productCartMapper.deleteBatch(list);
        }
    }
}
