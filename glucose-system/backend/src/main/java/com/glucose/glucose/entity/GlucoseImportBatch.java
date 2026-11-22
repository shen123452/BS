package com.glucose.glucose.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("glucose_import_batch")
public class GlucoseImportBatch {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long patientId;
    private String requestId;
    private String fileSha256;
    private String originalName;
    private String tempFileKey;
    private String dataSource;
    private String dataKind;
    private Integer sampleIntervalMinutes;
    private String status;
    private Integer totalRows;
    private Integer validRows;
    private Integer duplicateRows;
    private Integer conflictRows;
    private Integer errorRows;
    private Integer insertedRows;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private LocalDateTime expiresAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime revokedAt;
    private LocalDateTime createdAt;
}
