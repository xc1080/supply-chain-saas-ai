package com.simlect.controller.admin;

import com.simlect.entity.dto.MemberLevelRewardConfigDTO;
import com.simlect.entity.vo.ResponseVO;
import com.simlect.biz.MemberLevelRewardConfigService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RequestMapping("/admin/memberLevelRewardConfig")
@RestController
public class MemberLevelRewardConfigController extends com.simlect.controller.admin.ABaseController {

    @Resource
    private MemberLevelRewardConfigService memberLevelRewardConfigService;

    @PostMapping("/getConfig")
    public ResponseVO getConfig() {
        return getSuccessResponseVO(memberLevelRewardConfigService.getConfig());
    }

    @PostMapping("/saveConfig")
    public ResponseVO saveConfig(MemberLevelRewardConfigDTO config) {
        memberLevelRewardConfigService.saveConfig(config);
        return getSuccessResponseVO(null);
    }
}
