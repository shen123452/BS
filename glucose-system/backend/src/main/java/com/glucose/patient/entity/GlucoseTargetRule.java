package com.glucose.patient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("glucose_target_rule")
public class GlucoseTargetRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long patientId;
    private Integer measureType;
    private BigDecimal targetLow;
    private BigDecimal targetHigh;
    private String basis;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
