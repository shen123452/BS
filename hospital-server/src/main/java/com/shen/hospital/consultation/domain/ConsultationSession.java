package com.shen.hospital.consultation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.Data;

@Data
@TableName("consultation_session")
public class ConsultationSession {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long patientId;
    private String title;
    private String summaryJson;
    private Integer summaryVersion;
    private LocalDateTime summaryConfirmedAt;
    private Long summarySourceMessageId;
    private Long latestResultId;
    private String activeOperationId;
    private String activeOperationType;
    private LocalDateTime operationDeadline;
    private LocalDateTime deletedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
