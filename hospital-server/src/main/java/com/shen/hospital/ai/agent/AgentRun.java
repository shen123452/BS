package com.shen.hospital.ai.agent;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Data;

@Data @TableName("agent_run")
public class AgentRun {
    @TableId(type = IdType.AUTO) private Long id;
    private String runNo;
    private Long patientId;
    private Long sessionId;
    private String clientRequestId;
    private String requestHash;
    private Integer summaryVersion;
    private String inputSnapshot;
    private String modelName;
    private String configFingerprint;
    private String promptHash;
    private String toolNames;
    private String knowledgeSnapshot;
    private String safetyRuleVersion;
    private String status;
    private Integer maxRounds;
    private Integer maxToolCalls;
    private Integer actualRounds;
    private Integer actualToolCalls;
    private LocalDateTime deadlineAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String errorCode;
    private String errorMessage;
    private String traceId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
