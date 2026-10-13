package com.shen.hospital.schedule.domain;

import com.baomidou.mybatisplus.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.Data;

@Data
@TableName("doctor_schedule")
public class DoctorSchedule {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long doctorId;
    private Long departmentId;
    private LocalDate visitDate;
    private String timeSlot;
    private LocalDateTime bookingDeadline;
    private Integer totalCount;
    private Integer remainingCount;
    private BigDecimal fee;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableField(exist = false)
    private String doctorName;
    @TableField(exist = false)
    private String departmentName;
    @TableField(exist = false)
    private Boolean bookable;
}
