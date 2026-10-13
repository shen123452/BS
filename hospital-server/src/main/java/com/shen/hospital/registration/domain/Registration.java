package com.shen.hospital.registration.domain;

import com.baomidou.mybatisplus.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.Data;

@Data
@TableName("registration")
public class Registration {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String registrationNo;
    private Long patientId;
    private Long scheduleId;
    private Long doctorId;
    private Long departmentId;
    private String clientRequestId;
    private String requestHash;
    private Long triageResultId;
    private LocalDate visitDate;
    private String timeSlot;
    private String patientNameSnapshot;
    private String doctorNameSnapshot;
    private String departmentNameSnapshot;
    private BigDecimal feeSnapshot;
    private String symptomDescription;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime cancelledAt;
    private String cancelReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
