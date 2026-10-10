package com.ruoyi.system.service.impl;

import com.ruoyi.common.core.domain.entity.Product;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.bean.BeanValidators;
import com.ruoyi.system.mapper.ProductMapper;
import com.ruoyi.system.service.ProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.system.service.CommerceReceiptInventoryGuard;
import com.ruoyi.system.service.CommerceSkuCatalogService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import javax.validation.Validator;
import javax.validation.ConstraintViolationException;
import java.util.List;

/**
 * 货品资料Service业务层处理
 *
 * @author KrityCat
 */
@Service
public class ProductServiceImpl implements ProductService {

    @Autowired(required = false)
    private CommerceReceiptInventoryGuard commerceInventoryGuard;

    @Autowired(required = false)
    private CommerceSkuCatalogService skuCatalog;

    private static final Logger log = LoggerFactory.getLogger(ProductServiceImpl.class);

    @Autowired
    protected Validator validator;

    @Autowired
    private ProductMapper productMapper;

    /**
     * 货品资料查询
     */
    @Override
    public List<Product> productQuery(Product bo) {
        return productMapper.productQuery(bo);
    }

    /**
     * 货品库存积压预警查询
     */
    @Override
    public List<Product> productOverstockQuery(Product bo) {
        return productMapper.productOverstockQuery(bo);
    }

    /**
     * 货品库存不足预警查询
     */
    @Override
    public List<Product> productDangerQuery(Product bo) {
        return productMapper.productDangerQuery(bo);
    }

    /**
     * 积压3个月未出预警查询
     */
    @Override
    public List<Product> productThreeDangerQuery(Product bo) {
        return productMapper.productThreeDangerQuery(bo);
    }

    /**
     * 积压6个月未出预警查询
     */
    @Override
    public List<Product> productSixDangerQuery(Product bo) {
        return productMapper.productThreeDangerQuery(bo);
    }

    /**
     * 积压12个月未出预警查询
     */
    @Override
    public List<Product> productTwelveDangerQuery(Product bo) {
        return productMapper.productThreeDangerQuery(bo);
    }

    /**
     * 通过货品ID查询货品
     */
    @Override
    public Product selectProductById(Long productId) {
        return productMapper.selectProductById(productId);
    }


    /**
     * 新增保存货品资料
     *
     * @param bo 货品资料
     * @return 结果
     */
    @Override
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public int insertProduct(Product bo) {
        if(skuCatalog!=null)skuCatalog.guardProduct(bo,true);
        return productMapper.insertProduct(bo);
    }

    /**
     * 修改保存货品资料
     *
     * @param bo 货品资料
     * @return 结果
     */
    @Override
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public int updateProduct(Product bo) {
        if(skuCatalog!=null)skuCatalog.guardProduct(bo,false);
        return productMapper.updateProduct(bo);
    }

    /**
     * 删除货品资料
     *
     * @param productIds 需要删除的货品ID
     * @return 结果
     */
    @Override
    @Transactional
    public int deleteProductByIds(Long[] productIds) {
        if (commerceInventoryGuard != null) return commerceInventoryGuard.deleteProducts(productIds);
        return productMapper.deleteProductByIds(productIds);
    }

    /**
     * 导入货品数据
     *
     * @param productList     货品数据列表
     * @param isUpdateSupport 是否更新支持，如果已存在，则进行更新数据
     * @param operName        操作用户
     * @return 结果
     */
    @Override
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public String importProduct(List<Product> productList, Boolean isUpdateSupport, String operName) {
        if (StringUtils.isNull(productList) || productList.isEmpty()) {
            throw new ServiceException("导入货品数据不能为空！",400);
        }
        int successNum = 0;
        int failureNum = 0;
        int failureCode = 400;
        StringBuilder successMsg = new StringBuilder();
        StringBuilder failureMsg = new StringBuilder();
        for (Product bo : productList) {
            String label=bo==null?"未知货品":bo.getProductName();
            try {
                if (bo==null || StringUtils.isBlank(bo.getProductCode()) || StringUtils.isBlank(bo.getProductName()) || StringUtils.isBlank(bo.getProductType()) || StringUtils.isBlank(bo.getMeasureUnit())) {
                    throw new ServiceException("货品编号、货品名称、货品类型与计量单位不能为空！",400);
                }
                // SKU code identifies the import target; several variants may share one name.
                Product w=null;
                if(skuCatalog!=null) {
                    Long id=skuCatalog.importProductId(bo.getProductCode());
                    if(id!=null)w=productMapper.selectProductById(id);
                } else {
                    List<Product> matches=productMapper.selectProductsByCode(bo.getProductCode());
                    if(matches.size()>1)throw new ServiceException("旧数据存在重复货品编号，不能自动覆盖",409);
                    if(!matches.isEmpty())w=matches.get(0);
                }
                if (StringUtils.isNull(w)) {
                    BeanValidators.validateWithException(validator, bo);
                    bo.setProductName(bo.getProductName());
                    bo.setCreateBy(operName);
                    if(skuCatalog!=null)skuCatalog.guardProduct(bo,true);
                    productMapper.insertProduct(bo);
                    successNum++;
                    successMsg.append("<br/>" + successNum + "、货品 " + bo.getProductName() + " 导入成功");
                } else if (Boolean.TRUE.equals(isUpdateSupport)) {
                    BeanValidators.validateWithException(validator, bo);
//                    checkUserAllowed(w);
                    bo.setProductId(w.getProductId());
                    bo.setUpdateBy(operName);
                    if(skuCatalog!=null)skuCatalog.guardProduct(bo,false);
                    productMapper.updateProduct(bo);
                    successNum++;
                    successMsg.append("<br/>" + successNum + "、货品 " + bo.getProductName() + " 更新成功");
                } else {
                    failureNum++;
                    if(failureCode!=500)failureCode=409;
                    failureMsg.append("<br/>" + failureNum + "、货品 " + bo.getProductName() + " 已存在");
                }
            } catch (Exception e) {
                failureNum++;
                Integer code=e instanceof ServiceException?((ServiceException)e).getCode():null;
                if(Integer.valueOf(409).equals(code)) {if(failureCode!=500)failureCode=409;}
                else if(!Integer.valueOf(400).equals(code)&&!(e instanceof ConstraintViolationException))failureCode=500;
                String msg = "<br/>" + failureNum + "、货品 " + label + " 导入失败：";
                failureMsg.append(msg + e.getMessage());
                log.error(msg, e);
            }
        }
        if (failureNum > 0) {
            failureMsg.insert(0, "导入失败！共 " + failureNum + " 条货品未通过校验，错误如下：");
            throw new ServiceException(failureMsg.toString(),failureCode);
        } else {
            successMsg.insert(0, "恭喜您，数据已全部导入成功！共 " + successNum + " 条，数据如下：");
        }
        return successMsg.toString();

    }
}
