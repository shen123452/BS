package com.shen.hospital.masterdata.domain;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Data;

@Data @TableName("symptom_tag")
public class SymptomTag {
    @TableId(type = IdType.AUTO) private Long id;
    private String code;
    private String name;
    private String aliases;
    private String description;
    private Integer isRedFlag;
    private String confirmationQuestion;
    private String status;
    private Integer ruleRevision;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
