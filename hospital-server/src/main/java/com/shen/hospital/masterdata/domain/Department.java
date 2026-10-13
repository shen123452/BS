package com.shen.hospital.masterdata.domain;

import com.baomidou.mybatisplus.annotation.*;

import java.time.LocalDateTime;

import lombok.Data;

@Data
@TableName("department")
public class Department {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String description;
    private String location;
    private Integer isEmergency;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
