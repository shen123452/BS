package com.shen.hospital.masterdata.domain;

import com.baomidou.mybatisplus.annotation.*;

import java.time.LocalDateTime;

import lombok.Data;

@Data
@TableName("patient_profile")
public class PatientProfile {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String name;
    private String gender;
    private Integer age;
    private String chronicStatus;
    private String chronicHistory;
    private String allergyStatus;
    private String allergyHistory;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
