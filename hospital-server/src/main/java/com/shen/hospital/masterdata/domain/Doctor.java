package com.shen.hospital.masterdata.domain;

import com.baomidou.mybatisplus.annotation.*;

import java.time.LocalDateTime;

import lombok.Data;

@Data
@TableName("doctor")
public class Doctor {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long departmentId;
    private String name;
    private String title;
    private String specialty;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
