package com.simlect.biz;

import java.util.List;

import com.simlect.entity.query.StatisticsInfoQuery;
import com.simlect.entity.po.StatisticsInfo;
import com.simlect.entity.vo.PaginationResultVO;
import com.simlect.entity.vo.StatisticsDataVO;
import com.simlect.entity.vo.TodayDataVO;

public interface StatisticsInfoService {

	PaginationResultVO<StatisticsInfo> findListByPage(StatisticsInfoQuery param);

	List<TodayDataVO> getTodayData();

	List<StatisticsDataVO> loadWeeklyStatisticsData();

	void statistics(String startTime, String endTime);
}
