package com.shen.hospital.knowledge.dto;

import com.shen.hospital.knowledge.domain.KnowledgeDocument;
import java.time.LocalDateTime;

public record KnowledgeDocumentView(String id, String title, String sourceDescription, String documentVersion,
        String mimeType, long fileSize, boolean enabled, String status, String targetSpaceKey,
        int totalChunks, int embeddedChunks, String errorCode, String errorMessage, LocalDateTime createdAt,
        LocalDateTime updatedAt,String taskKind) {
    public static KnowledgeDocumentView of(KnowledgeDocument d) {
        return new KnowledgeDocumentView(d.getId().toString(), d.getTitle(), d.getSourceDescription(), d.getDocumentVersion(),
                d.getMimeType(), d.getFileSize(), Integer.valueOf(1).equals(d.getEnabled()), d.getStatus(), d.getTargetSpaceKey(),
                d.getTotalChunks() == null ? 0 : d.getTotalChunks(), d.getEmbeddedChunks() == null ? 0 : d.getEmbeddedChunks(),
                d.getErrorCode(), d.getErrorMessage(), d.getCreatedAt(), d.getUpdatedAt(),d.getTaskKind());
    }
}
