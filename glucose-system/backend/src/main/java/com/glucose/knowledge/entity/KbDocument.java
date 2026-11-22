package com.glucose.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("kb_document")
public class KbDocument {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;
    private String category;
    private String content;
    private String sourceTitle;
    private String sourceUrl;
    private String sourceVersion;
    private LocalDate publishedOn;
    private Integer documentVersion;
    private String contentHash;
    /** status=1 启用;0 停用(停用立即退出检索) */
    private Integer status;
    /** PENDING/BUILDING/READY/FAILED */
    private String indexStatus;
    private String activeIndexVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
