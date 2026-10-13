package com.shen.hospital.ai.domain;

import com.baomidou.mybatisplus.annotation.*;

import java.time.LocalDateTime;

import lombok.Data;

@Data
@TableName("ai_call_log")
public class AiCallLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String traceId;
    private String requestId;
    private Long runId;
    private Long stepId;
    private String purpose;
    private String modelName;
    private String configFingerprint;
    private String status;
    private Long inputTokens;
    private Long outputTokens;
    private Long totalTokens;
    private String usageSource;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private Long firstTokenLatencyMs;
    private String errorCode;
    private String errorMessage;
}
