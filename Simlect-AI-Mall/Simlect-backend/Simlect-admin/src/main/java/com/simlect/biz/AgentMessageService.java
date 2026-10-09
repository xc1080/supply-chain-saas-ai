package com.simlect.biz;

import com.simlect.entity.query.AgentMessageQuery;
import com.simlect.entity.po.AgentMessage;
import com.simlect.entity.vo.PaginationResultVO;

public interface AgentMessageService {

	PaginationResultVO<AgentMessage> findListByPage(AgentMessageQuery param);

	Integer deleteAgentMessageByMessageId(Integer messageId);
}
