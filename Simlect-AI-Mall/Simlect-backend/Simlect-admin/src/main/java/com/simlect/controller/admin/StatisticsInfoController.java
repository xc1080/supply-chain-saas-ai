package com.simlect.controller.admin;

import com.simlect.entity.query.StatisticsInfoQuery;
import com.simlect.entity.vo.ResponseVO;
import com.simlect.biz.StatisticsInfoService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

@RestController("statisticsInfoController")
@RequestMapping("/admin/statisticsInfo")
public class StatisticsInfoController extends com.simlect.controller.admin.ABaseController {

	@Resource
	private StatisticsInfoService statisticsInfoService;

	@PostMapping("/loadDataList")
	public ResponseVO loadDataList(StatisticsInfoQuery query) {
		query.setOrderBy("statistics_date desc, data_type asc");
		return getSuccessResponseVO(statisticsInfoService.findListByPage(query));
	}
}
