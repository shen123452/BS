package com.shen.hospital.ai.config;

import com.baomidou.mybatisplus.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data @TableName("ai_model_config_revision")
public class AiModelConfigRevision {
    @TableId(type = IdType.AUTO) private Long id;
    private Long configId;
    private Integer revision;
    private String provider;
    private String baseUrl;
    private String modelName;
    private String apiKey;
    private BigDecimal temperature;
    private Integer maxOutputTokens;
    private Integer timeoutMs;
    private Integer embeddingDimension;
    private String testStatus;
    private LocalDateTime testedAt;
    private String testDetail;
    private Long createdBy;
    private LocalDateTime createdAt;
}
