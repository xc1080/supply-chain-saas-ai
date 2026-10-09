package com.simlect.biz.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.simlect.biz.LocationWeatherService;
import com.simlect.component.RedisComponent;
import com.simlect.entity.config.AppConfig;
import com.simlect.entity.enums.ResponseCodeEnum;
import com.simlect.entity.vo.LocationWeatherVO;
import com.simlect.exception.BusinessException;
import com.simlect.utils.JsonUtils;
import com.simlect.utils.StringTools;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocationWeatherServiceImpl implements LocationWeatherService {

    @Resource
    private AppConfig appConfig;

    @Resource
    private OkHttpClient okHttpClient;

    @Resource
    private RedisComponent redisComponent;

    private static final String USER_AGENT = "Simlect/1.0 (location; contact=dev@simlect.local)";

    private static final String GEOCODE_URL =
            "https://restapi.amap.com/v3/geocode/regeo?location=%s,%s&key=%s&extensions=all";

    @Override
    public LocationWeatherVO resolve(Double lat, Double lng) {
        if (lat == null || lng == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        LocationWeatherVO vo = new LocationWeatherVO();
        vo.setLatitude(lat);
        vo.setLongitude(lng);

        fillGeocode(vo, lat, lng);
        buildSummary(vo);

        return vo;
    }

    private void fillGeocode(LocationWeatherVO vo, double lat, double lng) {
        String apiKey = appConfig.getAmapKey();
        if (StringTools.isEmpty(apiKey)) {
            log.warn("高德地图API Key未配置");
            return;
        }

        String url = String.format(GEOCODE_URL, lng, lat, apiKey);
        log.debug("查询地理编码 URL: {}", url);

        try {
            JsonNode json = getJson(url);
            if (json == null) {
                return;
            }
            String status = text(json, "status");
            if (!"1".equals(status)) {
                log.warn("高德地图API返回失败: {}", text(json, "info"));
                return;
            }
            JsonNode regeocode = json.get("regeocode");
            if (regeocode == null || regeocode.isNull()) {
                return;
            }
            JsonNode addressComponent = regeocode.get("addressComponent");
            if (addressComponent == null || addressComponent.isNull()) {
                return;
            }

            vo.setProvince(text(addressComponent, "province"));
            vo.setCity(text(addressComponent, "city"));
            vo.setDistrict(text(addressComponent, "district"));

            JsonNode streetNumber = addressComponent.get("streetNumber");
            if (streetNumber != null && !streetNumber.isNull()) {
                vo.setStreet(JsonUtils.toJson(streetNumber));
            }
        } catch (Exception e) {
            log.warn("逆地理编码失败 lat={}, lng={}", lat, lng, e);
        }
    }

    private void buildSummary(LocationWeatherVO vo) {
        String city = !StringTools.isEmpty(vo.getCity()) ? simplifyCityLabel(vo.getCity())
                : (!StringTools.isEmpty(vo.getDistrict()) ? simplifyCityLabel(vo.getDistrict())
                : (!StringTools.isEmpty(vo.getProvince()) ? simplifyCityLabel(vo.getProvince()) : "当地"));
        vo.setSummary(city);
    }

    private String simplifyCityLabel(String name) {
        if (StringTools.isEmpty(name)) {
            return name;
        }
        String s = name.trim();
        s = s.replace("市", "");
        s = s.replace("省", "");
        s = s.replace("自治区", "");
        s = s.replace("特别行政区", "");
        return s;
    }

    private JsonNode getJson(String url) {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build();

        for (int i = 0; i < 3; i++) {
            try (Response response = okHttpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("HTTP {} `{}`", response.code(), url);
                    if (i < 2) {
                        try {
                            Thread.sleep(6000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    continue;
                }
                String body = response.body() != null ? response.body().string() : null;
                if (StringTools.isEmpty(body)) {
                    log.warn("HTTP response body is empty: {}", url);
                    return null;
                }
                return JsonUtils.parseTree(body);
            } catch (IOException e) {
                log.warn("第{}次请求失败: `{}`  - {}", i + 1, url, e.getMessage());
                if (i < 2) {
                    try {
                        Thread.sleep(6000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        log.warn("请求重试2次后仍失败: `{}`", url);
        return null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value.isArray() || value.isObject()) {
            return JsonUtils.toJson(value);
        }
        return value.asText();
    }

    @Override
    public void saveUserCoords(String userId, Double latitude, Double longitude, LocationWeatherVO resolved) {
        com.simlect.entity.dto.UserLocationCoordsDTO coords = new com.simlect.entity.dto.UserLocationCoordsDTO();
        coords.setLatitude(latitude);
        coords.setLongitude(longitude);
        coords.setCity(resolved.getCity());
        coords.setUpdatedAt(System.currentTimeMillis());
        redisComponent.saveUserLocationCoords(userId, coords);
    }

    @Override
    public LocationWeatherVO syncUserLocation(String userId, Double latitude, Double longitude) {
        LocationWeatherVO resolved = resolve(latitude, longitude);
        if (resolved != null) {
            saveUserCoords(userId, latitude, longitude, resolved);
        }
        return resolved;
    }
}
