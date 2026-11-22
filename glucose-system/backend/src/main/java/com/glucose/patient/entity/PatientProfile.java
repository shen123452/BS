package com.glucose.patient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("patient_profile")
public class PatientProfile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long doctorId;
    private Integer gender;
    private LocalDate birthDate;
    private String diabetesType;
    private BigDecimal cgmTargetLow;
    private BigDecimal cgmTargetHigh;
    private Long targetRevision;
    private Long dataRevision;
    private String privacyVersion;
    private LocalDateTime privacyAcceptedAt;

    @TableLogic
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
