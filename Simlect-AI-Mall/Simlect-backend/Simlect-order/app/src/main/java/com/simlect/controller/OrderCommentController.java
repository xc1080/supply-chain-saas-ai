package com.simlect.controller;

import com.simlect.annotation.GlobalInterceptor;
import com.simlect.api.enums.CommentStatusEnum;
import com.simlect.entity.po.OrderComment;
import com.simlect.entity.query.OrderCommentQuery;
import com.simlect.entity.vo.PaginationResultVO;
import com.simlect.entity.vo.ResponseVO;
import com.simlect.biz.OrderCommentService;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
@RequestMapping("/order/comment")
@RestController
public class OrderCommentController extends ABaseController{

    @Resource
    private OrderCommentService orderCommentService;

    // 评价
    @PostMapping("/postComment")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO postComment(@NotEmpty String orderId, @NotEmpty @Size(max =300) String commentContent, @Size(max =2000) String commentImages, @NotNull @Min(1) @Max(5) Integer star){
        String userId = getTokenUserInfo().getUserId();
        boolean pendingReview = orderCommentService.postComment(userId, orderId, commentContent, commentImages, star);
        Map<String, Object> data = new HashMap<>();
        data.put("pendingReview", pendingReview);
        return getSuccessResponseVO(data);
    }

    // 获取商品所有评论
    @PostMapping("/loadComment")
    public ResponseVO loadComment(@NotNull Integer pageNo, @NotEmpty String productId){
        OrderCommentQuery orderCommentQuery = new OrderCommentQuery();
        orderCommentQuery.setProductId(productId);
        orderCommentQuery.setPageNo(pageNo);
        orderCommentQuery.setQueryUserInfo(true);
        orderCommentQuery.setStatus(CommentStatusEnum.NORMAL.getStatus());
        orderCommentQuery.setOrderBy("comment_time desc");
        PaginationResultVO<OrderComment> paginationResultVO = orderCommentService.findListByPage(orderCommentQuery);
        return getSuccessResponseVO(paginationResultVO);
    }

    // 获取单个orderId的评论
    @PostMapping("/getComment")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO getComment(@NotEmpty String orderId){
        String userId = getTokenUserInfo().getUserId();
        OrderComment orderComment = orderCommentService.getComment(userId,orderId);
        return getSuccessResponseVO(orderComment);
    }

    // 追评
    @PostMapping("/postReComment")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO postReComment(@NotEmpty String orderId, @NotEmpty @Size(max =300) String reCommentContent, @Size(max =2000) String reCommentImages){
        // 获取当前用户的userId
        String userId = getTokenUserInfo().getUserId();
        // 评价
        orderCommentService.postReComment(userId,orderId,reCommentContent,reCommentImages);
        return getSuccessResponseVO(null);
    }

    // 获取我的评论
    @PostMapping("/loadMyComment")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO loadMyComment(@NotNull Integer pageNo){
        String userId = getTokenUserInfo().getUserId();
        OrderCommentQuery orderCommentQuery = new OrderCommentQuery();
        orderCommentQuery.setUserId(userId);
        orderCommentQuery.setPageNo(pageNo);
        orderCommentQuery.setQueryUserInfo(true);
        orderCommentQuery.setQueryProduct(true);
        orderCommentQuery.setOrderBy("comment_time desc");
        orderCommentQuery.setStatus(CommentStatusEnum.NORMAL.getStatus());
        PaginationResultVO<OrderComment> paginationResultVO = orderCommentService.findListByPage(orderCommentQuery);
        return getSuccessResponseVO(paginationResultVO);
    }

    // 删除我的评论
    @PostMapping("/delMyComment")
    @GlobalInterceptor(checkLogin = true)
    public ResponseVO delMyComment(@NotEmpty String orderId){
        String userId = getTokenUserInfo().getUserId();
        orderCommentService.delMyComment(userId, orderId);
        return getSuccessResponseVO(null);
    }

    @PostMapping("/getProductCommentStats")
    public ResponseVO getProductCommentStats(String productId) {
        return getSuccessResponseVO(orderCommentService.getProductCommentStats(productId));
    }
}
