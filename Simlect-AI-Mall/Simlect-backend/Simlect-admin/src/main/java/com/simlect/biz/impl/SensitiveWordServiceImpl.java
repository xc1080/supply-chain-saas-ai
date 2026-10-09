package com.simlect.biz.impl;

import com.simlect.component.SensitiveWordCacheComponent;
import com.simlect.entity.po.SensitiveWord;
import com.simlect.entity.query.SensitiveWordQuery;
import com.simlect.entity.vo.PaginationResultVO;
import com.simlect.exception.BusinessException;
import com.simlect.mappers.SensitiveWordMapper;
import com.simlect.biz.SensitiveWordService;
import com.simlect.utils.StringTools;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Service("sensitiveWordService")
public class SensitiveWordServiceImpl implements SensitiveWordService {

    @Resource
    private SensitiveWordMapper<SensitiveWord, SensitiveWordQuery> sensitiveWordMapper;
    @Resource
    private SensitiveWordCacheComponent sensitiveWordCacheComponent;

    @PostConstruct
    public void init() {
        if (sensitiveWordCacheComponent.loadFromRedis() == null) {
            syncFromDbToRedis();
        }
    }

    @Override
    public PaginationResultVO<SensitiveWord> findListByPage(SensitiveWordQuery query) {
        int count = sensitiveWordMapper.selectCount(query);
        int pageSize = query.getPageSize() == null ? 15 : query.getPageSize();
        int pageNo = query.getPageNo() == null ? 1 : query.getPageNo();

        query.setSimplePage(new com.simlect.entity.query.SimplePage(pageNo, count, pageSize));
        query.setOrderBy("id desc");
        List<SensitiveWord> list = sensitiveWordMapper.selectList(query);

        return new PaginationResultVO<>(count, pageSize, pageNo, (count + pageSize - 1) / pageSize, list);
    }

    @Override
    public void save(Long id, String word, String replaceWord, Integer status) {
        if (StringTools.isEmpty(word)) {
            throw new BusinessException("敏感词不能为空");
        }
        word = word.trim();
        if (StringTools.isEmpty(replaceWord)) {
            replaceWord = "***";
        }

        SensitiveWord bean = new SensitiveWord();
        bean.setWord(word);
        bean.setReplaceWord(replaceWord);
        bean.setStatus(status == null ? 1 : status);
        bean.setUpdateTime(new Date());

        if (id != null) {
            bean.setId(id);
            SensitiveWordQuery updateQuery = new SensitiveWordQuery();
            updateQuery.setId(id);
            sensitiveWordMapper.updateByParam(bean, updateQuery);
        } else {
            bean.setCreateTime(new Date());
            sensitiveWordMapper.insert(bean);
        }

        syncFromDbToRedis();
    }

    @Override
    public void delete(Long id) {
        if (id == null) {
            throw new BusinessException("ID不能为空");
        }
        sensitiveWordMapper.deleteById(id);
        syncFromDbToRedis();
    }

    @Override
    public void refreshCache() {
        syncFromDbToRedis();
    }

    @Override
    public int syncFromDbToRedis() {
        SensitiveWordQuery query = new SensitiveWordQuery();
        query.setStatus(1);
        List<SensitiveWord> wordList = sensitiveWordMapper.selectList(query);
        sensitiveWordCacheComponent.saveToRedis(wordList);
        return wordList == null ? 0 : wordList.size();
    }
}
