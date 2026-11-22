package com.glucose.qa.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("qa_message")
public class QaMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;
    private String requestId;
    /** user / assistant */
    private String role;
    private String content;
    /** PROCESSING/COMPLETED/FAILED */
    private String status;
    /** RAG/RETRIEVAL_ONLY/NO_COVERAGE/FIXED_NOTICE */
    private String answerMode;
    /** 实际引用片段快照 JSON */
    private String sources;
    /** 区间、来源、样本量、覆盖、版本快照 JSON */
    private String contextSnapshot;
    private String model;
    private String embeddingModel;
    private String promptVersion;
    private Integer degraded;
    private Integer latencyMs;
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
