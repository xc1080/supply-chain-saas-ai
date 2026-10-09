package com.simlect.biz.impl;

import com.simlect.api.dto.ProductSnapshotBatchVO;
import com.simlect.api.support.ProductFeignSupport;
import com.simlect.api.vo.ProductInfoSnapshotVO;
import com.simlect.constants.Constants;
import com.simlect.entity.enums.PageSize;
import com.simlect.api.enums.ProductStatusEnum;
import com.simlect.entity.po.UserProductFavorite;
import com.simlect.entity.query.SimplePage;
import com.simlect.entity.query.UserProductFavoriteQuery;
import com.simlect.entity.vo.PaginationResultVO;
import com.simlect.api.vo.UserFavoriteProductVO;
import com.simlect.exception.BusinessException;
import com.simlect.mappers.UserProductFavoriteMapper;
import com.simlect.biz.UserProductFavoriteService;
import com.simlect.utils.StringTools;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service("userProductFavoriteService")
public class UserProductFavoriteServiceImpl implements UserProductFavoriteService {

    @Resource
    private UserProductFavoriteMapper<UserProductFavorite, UserProductFavoriteQuery> userProductFavoriteMapper;
    @Resource
    private ProductFeignSupport productFeignSupport;

    @Override
    public PaginationResultVO<UserFavoriteProductVO> loadFavoritePage(String userId, Integer pageNo) {
        UserProductFavoriteQuery query = new UserProductFavoriteQuery();
        query.setUserId(userId);
        query.setPageNo(pageNo);
        query.setOrderBy("create_time desc");
        int count = userProductFavoriteMapper.selectCount(query);
        int pageSize = PageSize.SIZE15.getSize();
        SimplePage page = new SimplePage(pageNo, count, pageSize);
        query.setSimplePage(page);
        List<UserProductFavorite> favorites = userProductFavoriteMapper.selectList(query);
        List<UserFavoriteProductVO> voList = new ArrayList<>();
        if (!favorites.isEmpty()) {
            List<String> productIds = favorites.stream().map(UserProductFavorite::getProductId).collect(Collectors.toList());
            ProductSnapshotBatchVO batch = productFeignSupport.snapshotBatch(productIds);
            Map<String, ProductInfoSnapshotVO> productMap = productFeignSupport.toProductInfoMap(batch);
            for (UserProductFavorite fav : favorites) {
                UserFavoriteProductVO vo = new UserFavoriteProductVO();
                vo.setFavoriteId(fav.getFavoriteId());
                vo.setProductId(fav.getProductId());
                vo.setCreateTime(fav.getCreateTime());
                ProductInfoSnapshotVO product = productMap.get(fav.getProductId());
                if (product != null) {
                    vo.setProductName(product.getProductName());
                    vo.setCover(resolveCover(product.getCover()));
                    vo.setStatus(product.getStatus());
                    vo.setMinPrice(product.getMinPrice());
                }
                voList.add(vo);
            }
        }
        return new PaginationResultVO<>(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), voList);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean toggleFavorite(String userId, String productId) {
        ProductSnapshotBatchVO batch = productFeignSupport.snapshotBatch(List.of(productId));
        Map<String, ProductInfoSnapshotVO> productMap = productFeignSupport.toProductInfoMap(batch);
        ProductInfoSnapshotVO product = productMap.get(productId);
        if (product == null || !ProductStatusEnum.ON_SALE.getStatus().equals(product.getStatus())) {
            throw new BusinessException("商品不存在或已下架");
        }
        UserProductFavoriteQuery query = new UserProductFavoriteQuery();
        query.setUserId(userId);
        query.setProductId(productId);
        List<UserProductFavorite> list = userProductFavoriteMapper.selectList(query);
        if (!list.isEmpty()) {
            userProductFavoriteMapper.deleteByFavoriteId(list.get(0).getFavoriteId());
            return false;
        }
        UserProductFavorite favorite = new UserProductFavorite();
        favorite.setFavoriteId("FAV" + StringTools.getRandomString(Constants.LENGTH_15));
        favorite.setUserId(userId);
        favorite.setProductId(productId);
        favorite.setCreateTime(new Date());
        userProductFavoriteMapper.insert(favorite);
        return true;
    }

    @Override
    public boolean isFavorite(String userId, String productId) {
        UserProductFavoriteQuery query = new UserProductFavoriteQuery();
        query.setUserId(userId);
        query.setProductId(productId);
        return userProductFavoriteMapper.selectCount(query) > 0;
    }

    @Override
    public void removeFavorite(String userId, String favoriteId) {
        UserProductFavorite favorite = userProductFavoriteMapper.selectByFavoriteId(favoriteId);
        if (favorite == null || !favorite.getUserId().equals(userId)) {
            throw new BusinessException("收藏不存在");
        }
        userProductFavoriteMapper.deleteByFavoriteId(favoriteId);
    }

    private String resolveCover(String cover) {
        if (StringTools.isEmpty(cover)) {
            return cover;
        }
        if (cover.contains(",")) {
            return cover.split(",")[0];
        }
        return cover;
    }
}
