package com.simlect.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.simlect.constants.Constants;
import com.simlect.entity.config.AppConfig;
import com.simlect.entity.dto.BaiduImageCensorResultDTO;
import com.simlect.entity.enums.ResponseCodeEnum;
import com.simlect.exception.BusinessException;
import com.simlect.redis.RedisUtils;
import com.simlect.utils.JsonUtils;
import com.simlect.utils.StringTools;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Slf4j
@Component
public class BaiduImageCensorComponent {

    private static final String TOKEN_URL =
            "https://aip.baidubce.com/oauth/2.0/token?grant_type=client_credentials&client_id=%s&client_secret=%s";
    private static final String CENSOR_URL =
            "https://aip.baidubce.com/rest/2.0/solution/v1/img_censor/v2/user_defined?access_token=%s";

    @Resource
    private AppConfig appConfig;
    @Resource
    private OkHttpClient okHttpClient;
    @Resource
    private RedisUtils redisUtils;

    public boolean isEnabled() {
        return Boolean.TRUE.equals(appConfig.getBaiduAipEnabled())
                && !StringTools.isEmpty(appConfig.getBaiduAipApiKey())
                && !StringTools.isEmpty(appConfig.getBaiduAipSecretKey());
    }

    public BaiduImageCensorResultDTO censorImage(byte[] imageBytes) {
        if (!isEnabled()) {
            BaiduImageCensorResultDTO skip = new BaiduImageCensorResultDTO();
            skip.setConclusionType(1);
            skip.setConclusion("未启用审核");
            skip.setRawResponse("baidu censor disabled");
            return skip;
        }
        if (imageBytes == null || imageBytes.length == 0) {
            throw new BusinessException(ResponseCodeEnum.CODE_600.getCode(), "图片内容为空");
        }
        String accessToken = getAccessToken();
        String imageBase64 = Base64.getEncoder().encodeToString(imageBytes);
        try {
            FormBody.Builder bodyBuilder = new FormBody.Builder(StandardCharsets.UTF_8)
                    .add("image", imageBase64)
                    .add("imgType", "0");
            if (appConfig.getBaiduAipStrategyId() != null) {
                bodyBuilder.add("strategyId", String.valueOf(appConfig.getBaiduAipStrategyId()));
            }
            Request request = new Request.Builder()
                    .url(String.format(CENSOR_URL, accessToken))
                    .post(bodyBuilder.build())
                    .build();
            try (Response response = okHttpClient.newCall(request).execute()) {
                if (response.body() == null) {
                    throw new BusinessException(ResponseCodeEnum.CODE_605.getCode(), "图像审核服务无响应");
                }
                String raw = response.body().string();
                return parseResult(raw);
            }
        } catch (IOException e) {
            log.error("调用百度图像审核失败", e);
            throw new BusinessException(ResponseCodeEnum.CODE_605.getCode(), "图像审核服务异常，请稍后重试");
        }
    }

    private BaiduImageCensorResultDTO parseResult(String raw) {
        JsonNode json = JsonUtils.parseTree(raw);
        if (json == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_605.getCode(), "图像审核结果解析失败");
        }
        if (json.has("error_code")) {
            String msg = text(json, "error_msg");
            log.warn("百度图像审核 error_code={}, msg={}", json.path("error_code").asInt(), msg);
            throw new BusinessException(ResponseCodeEnum.CODE_605.getCode(), toFriendlyCensorError(msg));
        }
        BaiduImageCensorResultDTO dto = new BaiduImageCensorResultDTO();
        if (json.has("conclusionType") && !json.get("conclusionType").isNull()) {
            dto.setConclusionType(json.get("conclusionType").asInt());
        }
        dto.setConclusion(text(json, "conclusion"));
        dto.setRawResponse(raw.length() > 4000 ? raw.substring(0, 4000) : raw);
        if (dto.getConclusionType() == null) {
            dto.setConclusionType(4);
        }
        return dto;
    }

    private static String toFriendlyCensorError(String msg) {
        if (StringTools.isEmpty(msg)) {
            return "图像审核失败，请更换图片后重试";
        }
        String lower = msg.toLowerCase();
        if (lower.contains("size") || msg.contains("大小") || msg.contains("尺寸") || msg.contains("分辨率")) {
            return "图像审核失败：图片过小或分辨率不符合要求（最短边需≥128px，Base64 后≥5KB），请换一张稍大的图片";
        }
        if (lower.contains("format") || msg.contains("格式") || msg.contains("216201") || msg.contains("216203")) {
            return "图像审核失败：图片格式无法识别，请重新截图或换一张图片";
        }
        return "图像审核失败：" + msg;
    }

    private String getAccessToken() {
        Object cached = redisUtils.get(Constants.REDIS_BAIDU_ACCESS_TOKEN);
        if (cached != null && !StringTools.isEmpty(String.valueOf(cached))) {
            return String.valueOf(cached);
        }
        String url = String.format(TOKEN_URL, appConfig.getBaiduAipApiKey(), appConfig.getBaiduAipSecretKey());
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            if (response.body() == null) {
                throw new BusinessException(ResponseCodeEnum.CODE_605.getCode(), "获取百度审核令牌失败");
            }
            JsonNode json = JsonUtils.parseTree(response.body().string());
            String token = text(json, "access_token");
            if (json == null || StringTools.isEmpty(token)) {
                throw new BusinessException(ResponseCodeEnum.CODE_605.getCode(), "获取百度审核令牌失败");
            }
            int expiresIn = json.path("expires_in").asInt(0);
            long ttl = Math.max(300, expiresIn - 600);
            redisUtils.setex(Constants.REDIS_BAIDU_ACCESS_TOKEN, token, ttl);
            return token;
        } catch (IOException e) {
            log.error("获取百度 access_token 失败", e);
            throw new BusinessException(ResponseCodeEnum.CODE_605.getCode(), "获取百度审核令牌失败");
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asText();
    }
}
