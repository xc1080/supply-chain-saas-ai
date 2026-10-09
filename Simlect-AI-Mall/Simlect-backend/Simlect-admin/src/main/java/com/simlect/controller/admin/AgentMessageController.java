package com.simlect.controller.admin;

import com.simlect.entity.query.AgentMessageQuery;
import com.simlect.entity.vo.ResponseVO;
import com.simlect.biz.AgentMessageService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

@RestController("agentMessageController")
@RequestMapping("/admin/agentMessage")
public class AgentMessageController extends com.simlect.controller.admin.ABaseController {

	@Resource
	private AgentMessageService agentMessageService;

	@PostMapping("/loadDataList")
	public ResponseVO loadDataList(AgentMessageQuery query) {
		return getSuccessResponseVO(agentMessageService.findListByPage(query));
	}

	@PostMapping("/deleteAgentMessageByMessageId")
	public ResponseVO deleteAgentMessageByMessageId(Integer messageId) {
		agentMessageService.deleteAgentMessageByMessageId(messageId);
		return getSuccessResponseVO(null);
	}
}
