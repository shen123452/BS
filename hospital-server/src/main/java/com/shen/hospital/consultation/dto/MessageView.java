package com.shen.hospital.consultation.dto;

import com.shen.hospital.consultation.domain.ConsultationMessage;

import java.time.LocalDateTime;

public record MessageView(String id, String role, String clientMessageId, String requestId,
                          String content, String status, LocalDateTime createdAt) {
    public static MessageView of(ConsultationMessage m) {
        return new MessageView(m.getId().toString(), m.getRole(), m.getClientMessageId(), m.getRequestId(),
                m.getContent(), m.getStatus(), m.getCreatedAt());
    }
}
