package com.simlect.biz;

import java.util.List;

import com.simlect.entity.query.OrderCommentQuery;
import com.simlect.entity.po.OrderComment;
import com.simlect.entity.vo.PaginationResultVO;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public interface OrderCommentService {

	List<OrderComment> findListByParam(OrderCommentQuery param);

	Integer findCountByParam(OrderCommentQuery param);

	PaginationResultVO<OrderComment> findListByPage(OrderCommentQuery param);

	Integer add(OrderComment bean);

	Integer addBatch(List<OrderComment> listBean);

	Integer addOrUpdateBatch(List<OrderComment> listBean);

	Integer updateByParam(OrderComment bean,OrderCommentQuery param);

	Integer deleteByParam(OrderCommentQuery param);

	OrderComment getOrderCommentByOrderId(String orderId);

	Integer updateOrderCommentByOrderId(OrderComment bean,String orderId);

	Integer deleteOrderCommentByOrderId(String orderId);

    boolean postComment(String userId, @NotEmpty String orderId, @NotEmpty String commentContent, String commentImages, @NotNull Integer star);

    int deletePendingCommentByOrderId(String orderId);

    int publishPendingComment(String orderId, String commentImages);

	OrderComment getComment(@NotEmpty String userId, @NotEmpty String orderId);

	void postReComment(String userId, @NotEmpty String orderId, @NotEmpty @Size(max =300) String reCommentContent, @Size(max =300) String reCommentImages);

	void delMyComment(String userId, @NotEmpty String orderId);

    void bizComment(String orderId, String commentContent, String commentImages);

	PaginationResultVO<OrderComment> findByNickNameFuzzy(PaginationResultVO<OrderComment> resultVO, String nickNameFuzzy);

	PaginationResultVO<OrderComment> findByProductNameFuzzy(PaginationResultVO<OrderComment> resultVO, String productNameFuzzy);

	com.simlect.api.vo.ProductCommentStatsVO getProductCommentStats(String productId);
}
