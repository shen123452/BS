package com.shen.hospital.consultation.dto;

import com.shen.hospital.consultation.domain.ConsultationSession;

import java.time.LocalDateTime;

public record SessionView(String id, String title, int summaryVersion, String summaryJson, boolean summaryConfirmed,
                          LocalDateTime createdAt, LocalDateTime updatedAt,String activeOperationId,String activeOperationType) {
    public static SessionView of(ConsultationSession s) {
        return new SessionView(s.getId().toString(), s.getTitle(), s.getSummaryVersion() == null ? 0 : s.getSummaryVersion(),
                s.getSummaryJson(), s.getSummaryConfirmedAt() != null, s.getCreatedAt(), s.getUpdatedAt(),s.getActiveOperationId(),s.getActiveOperationType());
    }
}
