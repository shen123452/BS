package com.shen.hospital.ai.config;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Data;

@Data @TableName("ai_model_config")
public class AiModelConfig {
    @TableId(type = IdType.AUTO) private Long id;
    private String code;
    private String name;
    private String modelType;
    private Long currentRevisionId;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
