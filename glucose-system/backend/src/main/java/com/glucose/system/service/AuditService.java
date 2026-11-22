package com.glucose.system.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glucose.common.web.TraceIdFilter;
import com.glucose.system.entity.OperationLog;
import com.glucose.system.mapper.OperationLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * 关键操作审计;失败不影响主流程。日志不记录凭据或完整健康问答。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final OperationLogMapper mapper;
    private final ObjectMapper objectMapper;

    public void record(Long actorId, String action, String resourceType, String resourceId,
                       String summary, Object details) {
        try {
            OperationLog log0 = new OperationLog();
            log0.setActorId(actorId);
            log0.setAction(action);
            log0.setResourceType(resourceType);
            log0.setResourceId(resourceId);
            log0.setSummary(summary);
            log0.setTraceId(MDC.get(TraceIdFilter.TRACE_ID));
            if (details != null) {
                log0.setDetails(objectMapper.writeValueAsString(details));
            }
            mapper.insert(log0);
        } catch (Exception e) {
            log.warn("audit log failed: {}", summary, e);
        }
    }
}
