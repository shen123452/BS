package com.shen.hospital.ai.agent;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Data;

@Data @TableName("triage_result")
public class TriageResult {
    @TableId(type = IdType.AUTO) private Long id;
    private Long runId;
    private Long patientId;
    private Long sessionId;
    private Integer summaryVersion;
    private Long departmentId;
    private String alternativeDepartmentIds;
    private String riskLevel;
    private String validationStatus;
    private String reason;
    private String evidenceRefs;
    private String doctorOptionsSnapshot;
    private String missingInformation;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
}
