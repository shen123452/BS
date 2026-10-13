package com.shen.hospital.registration.dto;

import java.time.LocalDateTime;

import lombok.Data;

@Data
public class TriageBookingState {
    private Long id;
    private Long departmentId;
    private String alternativeDepartmentIds;
    private String riskLevel;
    private String validationStatus;
    private Integer summaryVersion;
    private Integer currentSummaryVersion;
    private Long latestResultId;
    private LocalDateTime summaryConfirmedAt;
    private LocalDateTime deletedAt;
    private LocalDateTime expiresAt;
}
