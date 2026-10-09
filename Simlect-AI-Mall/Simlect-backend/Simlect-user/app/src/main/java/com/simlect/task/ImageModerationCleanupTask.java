package com.simlect.task;

import com.simlect.biz.ImageModerationService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ImageModerationCleanupTask {

    @Resource
    private ImageModerationService imageModerationService;

    @Scheduled(cron = "0 15 * * * ?")
    public void cleanupOrphanedCommentUploads() {
        try {
            int cleaned = imageModerationService.cleanupOrphanedCommentUploads();
            if (cleaned > 0) {
                log.info("定时清理孤立评论疑似图片完成，共 {} 条", cleaned);
            }
        } catch (Exception e) {
            log.error("定时清理孤立评论疑似图片失败", e);
        }
    }
}
