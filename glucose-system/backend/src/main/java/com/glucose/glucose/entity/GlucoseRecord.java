package com.glucose.glucose.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("glucose_record")
public class GlucoseRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long patientId;
    private LocalDateTime measuredAt;
    private BigDecimal glucoseMmol;
    private String dataSource;
    private String ingestMethod;
    private String dataKind;
    /** SMBG 必填;CGM 为 null */
    private Integer measureType;
    private Integer sampleIntervalMinutes;
    private Long batchId;
    private String riskFlag;
    private String note;

    @TableLogic
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
