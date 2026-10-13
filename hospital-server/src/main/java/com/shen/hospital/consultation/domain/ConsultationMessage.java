package com.shen.hospital.consultation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.Data;

@Data
@TableName("consultation_message")
public class ConsultationMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private String role;
    private String clientMessageId;
    private String requestId;
    private String content;
    private String status;
    private String errorCode;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
