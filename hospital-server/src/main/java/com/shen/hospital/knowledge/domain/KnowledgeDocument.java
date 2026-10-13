package com.shen.hospital.knowledge.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data @TableName("knowledge_document")
public class KnowledgeDocument {
    @TableId(type = IdType.AUTO) private Long id;
    private String title;
    private String fileKey;
    private String fileSha256;
    private String mimeType;
    private Long fileSize;
    private String sourceDescription;
    private String documentVersion;
    private Integer enabled;
    private String status;
    private String taskKind;
    private String targetSpaceKey;
    private String taskOperationId;
    private LocalDateTime taskDeadline;
    private Integer totalChunks;
    private Integer embeddedChunks;
    private Integer attemptNo;
    private String errorCode;
    private String errorMessage;
    private Long uploadedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
