package com.simlect.biz;

import com.simlect.entity.po.SensitiveWord;
import com.simlect.entity.query.SensitiveWordQuery;
import com.simlect.entity.vo.PaginationResultVO;

public interface SensitiveWordService {

    PaginationResultVO<SensitiveWord> findListByPage(SensitiveWordQuery query);

    void save(Long id, String word, String replaceWord, Integer status);

    void delete(Long id);

    void refreshCache();

    int syncFromDbToRedis();
}
