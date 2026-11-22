package com.glucose.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("operation_log")
public class OperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long actorId;
    private String action;
    private String resourceType;
    private String resourceId;
    private String summary;
    private String traceId;
    private String details;
    private LocalDateTime createdAt;
}
