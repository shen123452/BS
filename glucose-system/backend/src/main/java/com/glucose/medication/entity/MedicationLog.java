package com.glucose.medication.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.*;

@Data
@TableName("medication_log")
public class MedicationLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long patientId;
    private Long planId;
    private LocalDate recordDate;
    private String drugName;
    private String dosage;
    private LocalTime scheduledTime;
    private String instruction;
    private String status;
    private LocalDateTime takenAt;
    private Integer taken;
    private Long version;
    private LocalDateTime createdAt;
    @TableLogic private Integer deleted;
}
