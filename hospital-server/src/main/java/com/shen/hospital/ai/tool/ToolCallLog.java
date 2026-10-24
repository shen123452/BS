package com.shen.hospital.ai.tool;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Data;

@Data @TableName("mcp_tool_call_log")
public class ToolCallLog {
    @TableId(type = IdType.AUTO) private Long id;
    private Long runId;
    private Long stepId;
    private Long toolId;
    private String toolCodeSnapshot;
    private Integer toolRevision;
    private String toolCallId;
    private Long actorUserId;
    private String callSource;
    private String inputRedacted;
    private String outputSummary;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private String errorCode;
    private String traceId;
    private LocalDateTime createdAt;
}
