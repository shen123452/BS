package com.shen.hospital.ai.agent;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Data;

@Data @TableName("agent_run_event")
public class AgentRunEvent {
    @TableId(type = IdType.AUTO) private Long id;
    private Long runId;
    private Integer sequenceNo;
    private String eventType;
    private String payload;
    private String visibility;
    private LocalDateTime createdAt;
}
