package com.simlect.component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.houbb.sensitive.word.api.IWordDeny;
import com.simlect.utils.JsonUtils;
import com.github.houbb.sensitive.word.api.IWordResult;
import com.github.houbb.sensitive.word.bs.SensitiveWordBs;
import com.github.houbb.sensitive.word.support.deny.WordDenys;
import com.github.houbb.sensitive.word.support.result.WordResultHandlers;
import com.simlect.constants.Constants;
import com.simlect.entity.dto.SensitiveWordCacheItem;
import com.simlect.utils.StringTools;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class SensitiveWordFilter {

    private static final TypeReference<List<SensitiveWordCacheItem>> CACHE_LIST_TYPE =
            new TypeReference<>() {
            };

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private volatile SensitiveWordBs sensitiveWordBs;
    private volatile Map<String, String> replaceMap = new HashMap<>();
    private volatile long localVersion;

    @PostConstruct
    public void init() {
        reloadIfChanged();
    }

    @Scheduled(fixedRate = 60000)
    public void scheduledReload() {
        reloadIfChanged();
    }

    public String replaceSensitiveWords(String text) {
        if (StringTools.isEmpty(text)) {
            return text;
        }
        reloadIfChanged();
        SensitiveWordBs engine = sensitiveWordBs;
        if (engine == null) {
            return text;
        }
        List<IWordResult> results = engine.findAll(text, WordResultHandlers.raw());
        if (results.isEmpty()) {
            return text;
        }
        Map<String, String> currentReplaceMap = replaceMap;
        results.sort((a, b) -> b.startIndex() - a.startIndex());
        StringBuilder sb = new StringBuilder(text);
        for (IWordResult result : results) {
            String word = text.substring(result.startIndex(), result.endIndex());
            String replacement = currentReplaceMap.get(word);
            if (replacement == null) {
                replacement = "***";
            }
            sb.replace(result.startIndex(), result.endIndex(), replacement);
        }
        return sb.toString();
    }

    private void reloadIfChanged() {
        long redisVersion = getVersion();
        if (redisVersion > 0 && redisVersion == localVersion) {
            return;
        }
        List<SensitiveWordCacheItem> cached = loadFromRedis();
        if (cached == null) {
            return;
        }
        rebuildLocalEngine(cached);
        localVersion = redisVersion;
    }

    private List<SensitiveWordCacheItem> loadFromRedis() {
        String raw = stringRedisTemplate.opsForValue().get(Constants.REDIS_KEY_SENSITIVE_WORD_PAYLOAD);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return JsonUtils.parseObject(raw, CACHE_LIST_TYPE);
        } catch (Exception e) {
            log.warn("敏感词 Redis 缓存解析失败", e);
            return null;
        }
    }

    private long getVersion() {
        String version = stringRedisTemplate.opsForValue().get(Constants.REDIS_KEY_SENSITIVE_WORD_VERSION);
        if (version == null || version.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(version);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void rebuildLocalEngine(List<SensitiveWordCacheItem> items) {
        List<String> customWords = new ArrayList<>();
        Map<String, String> newReplaceMap = new HashMap<>();
        for (SensitiveWordCacheItem item : items) {
            if (item == null || StringTools.isEmpty(item.getWord())) {
                continue;
            }
            customWords.add(item.getWord());
            newReplaceMap.put(item.getWord(), item.getReplaceWord());
        }
        this.replaceMap = newReplaceMap;
        IWordDeny customDeny = () -> customWords;
        this.sensitiveWordBs = SensitiveWordBs.newInstance()
                .ignoreCase(true)
                .ignoreWidth(true)
                .ignoreNumStyle(true)
                .ignoreChineseStyle(true)
                .ignoreEnglishStyle(true)
                .ignoreRepeat(true)
                .enableUrlCheck(false)
                .enableEmailCheck(false)
                .wordDeny(WordDenys.chains(WordDenys.defaults(), customDeny))
                .init();
    }
}
