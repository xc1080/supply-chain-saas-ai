package com.simlect.biz;

import com.simlect.entity.vo.PaginationResultVO;
import com.simlect.api.vo.UserFavoriteProductVO;

public interface UserProductFavoriteService {

    PaginationResultVO<UserFavoriteProductVO> loadFavoritePage(String userId, Integer pageNo);

    boolean toggleFavorite(String userId, String productId);

    boolean isFavorite(String userId, String productId);

    void removeFavorite(String userId, String favoriteId);
}
