package com.simlect.biz.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.simlect.utils.JsonUtils;
import com.simlect.component.RedisComponent;
import com.simlect.constants.Constants;
import com.simlect.entity.enums.PageSize;
import com.simlect.entity.po.MqCompensationLog;
import com.simlect.entity.query.MqCompensationLogQuery;
import com.simlect.entity.query.RagSyncFailureQuery;
import com.simlect.entity.query.SimplePage;
import com.simlect.entity.vo.PaginationResultVO;
import com.simlect.entity.vo.RagSyncFailureVO;
import com.simlect.exception.BusinessException;
import com.simlect.mappers.MqCompensationLogMapper;
import com.simlect.service.MqCompensationLogService;
import com.simlect.biz.RagSyncFailureService;
import com.simlect.support.MqConsumeReplayRouter;
import com.simlect.utils.StringTools;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service("ragSyncFailureService")
public class RagSyncFailureServiceImpl implements RagSyncFailureService {

    @Resource
    private MqCompensationLogMapper<MqCompensationLog, MqCompensationLogQuery> mqCompensationLogMapper;
    @Resource
    private MqCompensationLogService mqCompensationLogService;
    @Resource
    private RedisComponent redisComponent;

    @Override
    public PaginationResultVO<RagSyncFailureVO> loadList(RagSyncFailureQuery param) {
        if ("REDIS_DLQ".equals(param.getSource())) {
            return loadRedisOnly(param);
        }
        MqCompensationLogQuery dbQuery = new MqCompensationLogQuery();
        dbQuery.setRagRelatedOnly(true);
        dbQuery.setStatus(param.getStatus());
        dbQuery.setOrderBy("log_id desc");
        dbQuery.setPageNo(param.getPageNo());
        int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();
        dbQuery.setPageSize(pageSize);

        int count = mqCompensationLogMapper.selectCount(dbQuery);
        SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
        dbQuery.setSimplePage(page);
        List<MqCompensationLog> rows = mqCompensationLogMapper.selectList(dbQuery);

        List<RagSyncFailureVO> voList = new ArrayList<>();
        if (rows != null) {
            for (MqCompensationLog row : rows) {
                RagSyncFailureVO vo = toVo(row);
                if (matchesFilter(vo, param)) {
                    voList.add(vo);
                }
            }
        }
        return new PaginationResultVO<>(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), voList);
    }

    private PaginationResultVO<RagSyncFailureVO> loadRedisOnly(RagSyncFailureQuery param) {
        int pageNo = param.getPageNo() == null ? 1 : param.getPageNo();
        int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();
        long total = redisComponent.countRagFailRedisSnapshots();
        int offset = (pageNo - 1) * pageSize;
        List<RagSyncFailureVO> list = redisComponent.listRagFailRedisSnapshots(offset, pageSize);
        if (!StringTools.isEmpty(param.getDataIdFuzzy())) {
            list = list.stream()
                    .filter(v -> v.getDataId() != null && v.getDataId().contains(param.getDataIdFuzzy()))
                    .collect(Collectors.toList());
        }
        if (!StringTools.isEmpty(param.getDataType())) {
            list = list.stream()
                    .filter(v -> param.getDataType().equalsIgnoreCase(v.getDataType()))
                    .collect(Collectors.toList());
        }
        int pageTotal = pageSize <= 0 ? 0 : (int) ((total + pageSize - 1) / pageSize);
        return new PaginationResultVO<>((int) total, pageSize, pageNo, pageTotal, list);
    }

    private boolean matchesFilter(RagSyncFailureVO vo, RagSyncFailureQuery param) {
        if (!StringTools.isEmpty(param.getDataIdFuzzy())
                && (vo.getDataId() == null || !vo.getDataId().contains(param.getDataIdFuzzy()))) {
            return false;
        }
        if (!StringTools.isEmpty(param.getDataType())
                && !param.getDataType().equalsIgnoreCase(vo.getDataType())) {
            return false;
        }
        if (!StringTools.isEmpty(param.getSource()) && !param.getSource().equals(vo.getSource())) {
            return false;
        }
        return true;
    }

    private RagSyncFailureVO toVo(MqCompensationLog row) {
        RagSyncFailureVO vo = new RagSyncFailureVO();
        vo.setLogId(row.getLogId());
        vo.setIdempotencyKey(row.getIdempotencyKey());
        vo.setQueueName(row.getRoutingKey());
        vo.setErrorMessage(row.getErrorMessage());
        vo.setRetryCount(row.getRetryCount());
        vo.setStatus(row.getStatus());
        vo.setHandleRemark(row.getHandleRemark());
        vo.setCreateTime(row.getCreateTime());
        vo.setHandleTime(row.getHandleTime());
        vo.setPayloadJson(row.getPayloadJson());
        if (MqConsumeReplayRouter.isConsumeFailure(row.getExchange())) {
            vo.setSource("CONSUME");
        } else {
            vo.setSource("SEND");
        }
        parsePayload(row.getPayloadJson(), vo);
        return vo;
    }

    private void parsePayload(String payloadJson, RagSyncFailureVO vo) {
        if (StringTools.isEmpty(payloadJson)) {
            return;
        }
        try {
            JsonNode obj = JsonUtils.parseTree(payloadJson);
            if (obj == null) {
                return;
            }
            if (obj.has("dataId")) {
                vo.setDataId(obj.get("dataId").asText());
            }
            if (obj.has("type")) {
                vo.setDataType(obj.get("type").asText());
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void replay(Integer logId) {
        mqCompensationLogService.replay(logId);
    }

    @Override
    public void updateStatus(Integer logId, Integer status, String handleRemark) {
        mqCompensationLogService.updateHandleStatus(logId, status, handleRemark);
    }

    @Override
    public void dismissRedisSnapshot(String dataId, String dataType) {
        redisComponent.removeRagFailRedisSnapshot(dataId, dataType);
    }
}
