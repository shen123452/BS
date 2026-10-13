package com.shen.hospital.ai.agent;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Data;

@Data @TableName("agent_step")
public class AgentStep {
    @TableId(type = IdType.AUTO) private Long id;
    private Long runId;
    private Integer sequenceNo;
    private Integer roundNo;
    private String stepType;
    private String toolName;
    private String toolCallId;
    private String status;
    private String inputRedacted;
    private String outputSummary;
    private String evidenceChunkIds;
    private String publicLabel;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private String errorCode;
    private LocalDateTime createdAt;
}
