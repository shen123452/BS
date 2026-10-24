package com.shen.hospital.masterdata.domain;

import com.baomidou.mybatisplus.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data @TableName("symptom_department_rule")
public class SymptomDepartmentRule {
    @TableId(type = IdType.AUTO) private Long id;
    private Long symptomTagId;
    private Long departmentId;
    private BigDecimal weight;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
