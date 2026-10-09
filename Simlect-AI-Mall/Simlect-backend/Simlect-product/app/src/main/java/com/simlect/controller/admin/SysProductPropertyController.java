package com.simlect.controller.admin;

import java.util.List;

import com.simlect.entity.query.SysProductPropertyQuery;
import com.simlect.entity.po.SysProductProperty;
import com.simlect.entity.vo.ResponseVO;
import com.simlect.biz.SysProductPropertyService;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

@RestController("sysProductPropertyController")
@RequestMapping("/admin/sysProductProperty")
public class SysProductPropertyController extends com.simlect.controller.admin.ABaseController{

	@Resource
	private SysProductPropertyService sysProductPropertyService;

	@PostMapping("/loadDataList")
	public ResponseVO loadDataList(SysProductPropertyQuery query){
		return getSuccessResponseVO(sysProductPropertyService.findListByPage(query));
	}

	@PostMapping("/add")
	public ResponseVO add(SysProductProperty bean) {
		sysProductPropertyService.add(bean);
		return getSuccessResponseVO(null);
	}

	@PostMapping("/addBatch")
	public ResponseVO addBatch(@RequestBody List<SysProductProperty> listBean) {
		sysProductPropertyService.addBatch(listBean);
		return getSuccessResponseVO(null);
	}

	@PostMapping("/addOrUpdateBatch")
	public ResponseVO addOrUpdateBatch(@RequestBody List<SysProductProperty> listBean) {
		sysProductPropertyService.addBatch(listBean);
		return getSuccessResponseVO(null);
	}

	@PostMapping("/getSysProductPropertyByPropertyId")
	public ResponseVO getSysProductPropertyByPropertyId(String propertyId) {
		return getSuccessResponseVO(sysProductPropertyService.getSysProductPropertyByPropertyId(propertyId));
	}

	@PostMapping("/updateSysProductPropertyByPropertyId")
	public ResponseVO updateSysProductPropertyByPropertyId(SysProductProperty bean,String propertyId) {
		sysProductPropertyService.updateSysProductPropertyByPropertyId(bean,propertyId);
		return getSuccessResponseVO(null);
	}

	@PostMapping("/deleteSysProductPropertyByPropertyId")
	public ResponseVO deleteSysProductPropertyByPropertyId(String propertyId) {
		sysProductPropertyService.deleteSysProductPropertyByPropertyId(propertyId);
		return getSuccessResponseVO(null);
	}
}
