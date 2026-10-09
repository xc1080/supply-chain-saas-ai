package com.simlect.biz;

import com.simlect.api.dto.ProductSnapshotBatchVO;
import com.simlect.api.vo.ProductInfoSnapshotVO;
import com.simlect.api.vo.ProductPropertyValueSnapshotVO;
import com.simlect.api.vo.ProductRagIndexVO;
import com.simlect.api.vo.ProductRagPropertyVO;
import com.simlect.api.vo.ProductRagSkuVO;
import com.simlect.api.vo.ProductSearchIndexVO;
import com.simlect.api.vo.ProductSkuSnapshotVO;
import com.simlect.api.enums.ProductStatusEnum;
import com.simlect.entity.po.ProductInfo;
import com.simlect.entity.po.ProductPropertyValue;
import com.simlect.entity.po.ProductSku;
import com.simlect.entity.query.ProductInfoQuery;
import com.simlect.entity.query.ProductPropertyValueQuery;
import com.simlect.entity.query.ProductSkuQuery;
import com.simlect.exception.BusinessException;
import com.simlect.mappers.ProductInfoMapper;
import com.simlect.mappers.ProductPropertyValueMapper;
import com.simlect.mappers.ProductSkuMapper;
import com.simlect.utils.StringTools;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ProductInternalService {

    @Resource
    private ProductInfoMapper<ProductInfo, ProductInfoQuery> productInfoMapper;
    @Resource
    private ProductSkuMapper<ProductSku, ProductSkuQuery> productSkuMapper;
    @Resource
    private ProductPropertyValueMapper<ProductPropertyValue, ProductPropertyValueQuery> productPropertyValueMapper;

    public ProductSnapshotBatchVO snapshotBatch(List<String> productIds) {
        ProductSnapshotBatchVO vo = new ProductSnapshotBatchVO();
        if (CollectionUtils.isEmpty(productIds)) {
            vo.setProducts(Collections.emptyList());
            vo.setSkus(Collections.emptyList());
            vo.setPropertyValues(Collections.emptyList());
            return vo;
        }
        ProductInfoQuery productInfoQuery = new ProductInfoQuery();
        productInfoQuery.setProductIdList(productIds);
        List<ProductInfo> products = productInfoMapper.selectList(productInfoQuery);
        List<ProductInfoSnapshotVO> productVos = new ArrayList<>();
        for (ProductInfo p : products) {
            ProductInfoSnapshotVO item = new ProductInfoSnapshotVO();
            item.setProductId(p.getProductId());
            item.setProductName(p.getProductName());
            item.setStatus(p.getStatus());
            item.setCover(p.getCover());
            item.setMinPrice(p.getMinPrice());
            item.setCategoryId(p.getCategoryId());
            item.setTotalSale(p.getTotalSale());
            productVos.add(item);
        }
        vo.setProducts(productVos);

        ProductSkuQuery productSkuQuery = new ProductSkuQuery();
        productSkuQuery.setProductIdList(productIds);
        productSkuQuery.setOrderBy("sort asc");
        List<ProductSku> skus = productSkuMapper.selectList(productSkuQuery);
        List<ProductSkuSnapshotVO> skuVos = new ArrayList<>();
        for (ProductSku s : skus) {
            ProductSkuSnapshotVO item = new ProductSkuSnapshotVO();
            BeanUtils.copyProperties(s, item);
            skuVos.add(item);
        }
        vo.setSkus(skuVos);

        ProductPropertyValueQuery propertyValueQuery = new ProductPropertyValueQuery();
        propertyValueQuery.setProductIdList(productIds);
        List<ProductPropertyValue> pvs = productPropertyValueMapper.selectList(propertyValueQuery);
        List<ProductPropertyValueSnapshotVO> pvVos = new ArrayList<>();
        for (ProductPropertyValue pv : pvs) {
            ProductPropertyValueSnapshotVO item = new ProductPropertyValueSnapshotVO();
            item.setProductId(pv.getProductId());
            item.setPropertyValueId(pv.getPropertyValueId());
            item.setPropertyName(pv.getPropertyName());
            item.setPropertyValue(pv.getPropertyValue());
            item.setPropertyCover(pv.getPropertyCover());
            pvVos.add(item);
        }
        vo.setPropertyValues(pvVos);
        return vo;
    }

    public ProductSkuSnapshotVO defaultSku(String productId) {
        if (StringTools.isEmpty(productId)) {
            throw new BusinessException("商品ID为空");
        }
        ProductSkuQuery query = new ProductSkuQuery();
        query.setProductId(productId);
        query.setOrderBy("sort asc");
        List<ProductSku> list = productSkuMapper.selectList(query);
        if (list == null || list.isEmpty()) {
            return null;
        }
        ProductSkuSnapshotVO vo = new ProductSkuSnapshotVO();
        BeanUtils.copyProperties(list.get(0), vo);
        return vo;
    }

    @Transactional(rollbackFor = Exception.class)
    public void increaseSales(String productId, int qty) {
        if (StringTools.isEmpty(productId) || qty <= 0) {
            return;
        }
        ProductInfo product = productInfoMapper.selectByProductId(productId);
        if (product == null) {
            throw new BusinessException("商品不存在");
        }
        int oldSale = product.getTotalSale() == null ? 0 : product.getTotalSale();
        ProductInfo patch = new ProductInfo();
        patch.setTotalSale(oldSale + qty);
        productInfoMapper.updateByProductId(patch, productId);
    }

    public ProductSearchIndexVO getSearchIndex(String productId) {
        if (StringTools.isEmpty(productId)) {
            return null;
        }
        ProductInfo product = productInfoMapper.selectByProductId(productId);
        if (product == null) {
            return null;
        }
        ProductSearchIndexVO vo = new ProductSearchIndexVO();
        vo.setProductId(product.getProductId());
        vo.setProductName(product.getProductName());
        vo.setProductDesc(product.getProductDesc());
        vo.setCover(product.getCover());
        vo.setCategoryId(product.getCategoryId());
        vo.setMinPrice(product.getMinPrice());
        vo.setMaxPrice(product.getMaxPrice());
        vo.setTotalSale(product.getTotalSale());
        vo.setStatus(product.getStatus());
        return vo;
    }

    public ProductRagIndexVO getRagIndex(String productId) {
        if (StringTools.isEmpty(productId)) {
            return null;
        }
        ProductInfo product = productInfoMapper.selectByProductId(productId);
        if (product == null) {
            return null;
        }
        ProductRagIndexVO vo = new ProductRagIndexVO();
        vo.setProductId(product.getProductId());
        vo.setProductName(product.getProductName());
        vo.setStatus(product.getStatus());

        ProductPropertyValueQuery propertyValueQuery = new ProductPropertyValueQuery();
        propertyValueQuery.setProductId(productId);
        propertyValueQuery.setOrderBy("property_sort asc");
        List<ProductPropertyValue> dbPropertyList = productPropertyValueMapper.selectList(propertyValueQuery);
        List<ProductRagPropertyVO> propertyVos = new ArrayList<>();
        if (dbPropertyList != null) {
            for (ProductPropertyValue pv : dbPropertyList) {
                ProductRagPropertyVO item = new ProductRagPropertyVO();
                item.setPropertyValueId(pv.getPropertyValueId());
                item.setPropertyName(pv.getPropertyName());
                item.setPropertyValue(pv.getPropertyValue());
                propertyVos.add(item);
            }
        }
        vo.setPropertyValues(propertyVos);

        ProductSkuQuery productSkuQuery = new ProductSkuQuery();
        productSkuQuery.setProductId(productId);
        List<ProductSku> dbSkuList = productSkuMapper.selectList(productSkuQuery);
        List<ProductRagSkuVO> skuVos = new ArrayList<>();
        if (dbSkuList != null) {
            for (ProductSku sku : dbSkuList) {
                ProductRagSkuVO item = new ProductRagSkuVO();
                item.setPropertyValueIds(sku.getPropertyValueIds());
                item.setPropertyValueIdHash(sku.getPropertyValueIdHash());
                skuVos.add(item);
            }
        }
        vo.setSkus(skuVos);
        return vo;
    }

    public List<String> listOnSaleProductIds() {
        ProductInfoQuery query = new ProductInfoQuery();
        query.setStatus(ProductStatusEnum.ON_SALE.getStatus());
        List<ProductInfo> list = productInfoMapper.selectList(query);
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        return list.stream().map(ProductInfo::getProductId).collect(Collectors.toList());
    }
}
