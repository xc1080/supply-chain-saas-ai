package com.simlect.biz;

import com.simlect.entity.dto.BaiduImageCensorResultDTO;
import com.simlect.api.dto.ImageUploadResultDTO;
import com.simlect.entity.po.ImageModerationRecord;
import com.simlect.entity.query.ImageModerationRecordQuery;
import com.simlect.entity.vo.PaginationResultVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface ImageModerationService {

    ImageUploadResultDTO uploadAndModerate(String userId, String userIp, MultipartFile file,
                                           Boolean createThumbnail, String scene, String orderId);

    BaiduImageCensorResultDTO censorImageBytes(byte[] imageBytes, String userId, String userIp);

    List<ImageModerationRecord> findListByParam(ImageModerationRecordQuery param);

    PaginationResultVO<ImageModerationRecord> findListByPage(ImageModerationRecordQuery param);

    ImageModerationRecord getByRecordId(Integer recordId);

    void handleReview(Integer recordId, String action, String handleRemark);

    void validateCommentQuarantinePaths(String userId, String orderId, String commentImages);

    boolean containsQuarantinePath(String commentImages);

    int cleanupOrphanedCommentUploads();
}
