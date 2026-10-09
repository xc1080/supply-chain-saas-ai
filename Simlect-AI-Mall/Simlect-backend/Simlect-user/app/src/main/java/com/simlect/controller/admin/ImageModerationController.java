package com.simlect.controller.admin;

import com.simlect.component.UserTempBanService;
import com.simlect.entity.dto.BaiduImageCensorResultDTO;
import com.simlect.entity.query.ImageModerationRecordQuery;
import com.simlect.entity.vo.ResponseVO;
import com.simlect.biz.ImageModerationService;
import com.simlect.exception.BusinessException;
import com.simlect.utils.FileUtils;
import com.simlect.utils.StringTools;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@RestController("imageModerationController")
@RequestMapping("/admin/imageModeration")
public class ImageModerationController extends com.simlect.controller.admin.ABaseController {

    @Resource
    private ImageModerationService imageModerationService;
    @Resource
    private FileUtils fileUtils;
    @Resource
    private UserTempBanService userTempBanService;

    @PostMapping("/loadDataList")
    public ResponseVO loadDataList(ImageModerationRecordQuery query) {
        if (query.getOrderBy() == null) {
            query.setOrderBy("create_time desc");
        }
        return getSuccessResponseVO(imageModerationService.findListByPage(query));
    }

    @PostMapping("/getByRecordId")
    public ResponseVO getByRecordId(Integer recordId) {
        return getSuccessResponseVO(imageModerationService.getByRecordId(recordId));
    }

    @PostMapping("/handleReview")
    public ResponseVO handleReview(Integer recordId, String action, String handleRemark) {
        imageModerationService.handleReview(recordId, action, handleRemark);
        return getSuccessResponseVO(null);
    }

    @PostMapping("/getTempBanInfo")
    public ResponseVO getTempBanInfo(String userId) {
        Long unbanAt = userTempBanService.getUnbanAtMs(userId);
        Map<String, Object> data = new HashMap<>();
        data.put("tempBanned", unbanAt != null);
        data.put("unbanAt", unbanAt);
        return getSuccessResponseVO(data);
    }

    @PostMapping("/unbanUser")
    public ResponseVO unbanUser(String userId) {
        if (!userTempBanService.manualUnban(userId)) {
            throw new BusinessException("该用户当前非临时封禁状态，无法解封");
        }
        return getSuccessResponseVO(null);
    }

    @PostMapping("/censorImage")
    public ResponseVO censorImage(@NotNull MultipartFile file) {
        try {
            BaiduImageCensorResultDTO result = imageModerationService.censorImageBytes(
                    file.getBytes(), "admin-test", "127.0.0.1");
            return getSuccessResponseVO(result);
        } catch (Exception e) {
            throw new BusinessException(StringTools.isEmpty(e.getMessage()) ? "审核失败" : e.getMessage());
        }
    }
}
