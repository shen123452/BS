package com.glucose.medication.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.*;

@Data
@TableName("medication_plan")
public class MedicationPlan {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long patientId;
    private String requestId;
    private String drugName;
    private String dosage;
    private LocalTime scheduledTime;
    private LocalDate startDate;
    private LocalDate endDate;
    private String instruction;
    private Integer active;
    private LocalDate stoppedDate;
    private Long version;
    private LocalDateTime createdAt;
}
