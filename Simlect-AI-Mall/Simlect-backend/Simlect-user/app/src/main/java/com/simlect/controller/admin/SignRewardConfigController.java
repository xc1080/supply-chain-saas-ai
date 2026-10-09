package com.simlect.controller.admin;

import com.simlect.entity.dto.SignRewardConfigDTO;
import com.simlect.entity.vo.ResponseVO;
import com.simlect.biz.SignRewardConfigService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RequestMapping("/admin/signRewardConfig")
@RestController
public class SignRewardConfigController extends com.simlect.controller.admin.ABaseController {

    @Resource
    private SignRewardConfigService signRewardConfigService;

    @PostMapping("/getConfig")
    public ResponseVO getConfig() {
        return getSuccessResponseVO(signRewardConfigService.getConfig());
    }

    @PostMapping("/saveConfig")
    public ResponseVO saveConfig(SignRewardConfigDTO config) {
        signRewardConfigService.saveConfig(config);
        return getSuccessResponseVO(null);
    }
}
