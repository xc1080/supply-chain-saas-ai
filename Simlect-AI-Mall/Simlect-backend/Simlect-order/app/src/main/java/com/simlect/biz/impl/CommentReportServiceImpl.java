package com.simlect.biz.impl;

import com.simlect.entity.enums.PageSize;
import com.simlect.entity.po.CommentReport;
import com.simlect.entity.po.OrderComment;
import com.simlect.entity.query.CommentReportQuery;
import com.simlect.entity.query.SimplePage;
import com.simlect.entity.vo.PaginationResultVO;
import com.simlect.exception.BusinessException;
import com.simlect.mappers.CommentReportMapper;
import com.simlect.biz.CommentReportService;
import com.simlect.biz.OrderCommentService;
import com.simlect.utils.StringTools;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Service("commentReportService")
public class CommentReportServiceImpl implements CommentReportService {

    @Resource
    private CommentReportMapper<CommentReport, CommentReportQuery> commentReportMapper;

    @Resource
    private OrderCommentService orderCommentService;

    @Override
    public List<CommentReport> findListByParam(CommentReportQuery param) {
        return this.commentReportMapper.selectList(param);
    }

    @Override
    public Integer findCountByParam(CommentReportQuery param) {
        return this.commentReportMapper.selectCount(param);
    }

    @Override
    public PaginationResultVO<CommentReport> findListByPage(CommentReportQuery param) {
        int count = this.findCountByParam(param);
        int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();
        SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
        param.setSimplePage(page);
        List<CommentReport> list = this.findListByParam(param);
        return new PaginationResultVO<>(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
    }

    @Override
    public CommentReport submitReport(String reporterUserId, String orderId, String productId,
                                      String reason, String detail, String commentSnapshot) {
        if (StringTools.isEmpty(orderId)) {
            throw new BusinessException("缺少被举报评论标识");
        }
        if (StringTools.isEmpty(reason)) {
            throw new BusinessException("请选择举报理由");
        }
        OrderComment comment = orderCommentService.getOrderCommentByOrderId(orderId);
        if (comment != null && reporterUserId.equals(comment.getUserId())) {
            throw new BusinessException("不能举报自己的评论");
        }
        CommentReport bean = new CommentReport();
        bean.setOrderId(orderId);
        bean.setProductId(productId);
        bean.setReporterUserId(reporterUserId);
        bean.setReason(reason);
        bean.setDetail(detail);
        bean.setCommentSnapshot(commentSnapshot);
        bean.setStatus(0);
        bean.setReportTime(new Date());
        this.commentReportMapper.insert(bean);
        return bean;
    }

    @Override
    public CommentReport getByReportId(Integer reportId) {
        return this.commentReportMapper.selectByReportId(reportId);
    }

    @Override
    public Integer handleReport(Integer reportId, Integer status, String handleRemark) {
        if (reportId == null) {
            throw new BusinessException("缺少举报ID");
        }
        CommentReport bean = new CommentReport();
        bean.setStatus(status == null ? 1 : status);
        bean.setHandleRemark(handleRemark);
        bean.setHandleTime(new Date());
        return this.commentReportMapper.updateByReportId(bean, reportId);
    }

    @Override
    public Integer deleteByReportId(Integer reportId) {
        return this.commentReportMapper.deleteByReportId(reportId);
    }
}
