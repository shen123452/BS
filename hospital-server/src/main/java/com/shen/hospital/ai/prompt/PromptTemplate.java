package com.shen.hospital.ai.prompt;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Data;

@Data @TableName("prompt_template")
public class PromptTemplate {
    @TableId(type = IdType.AUTO) private Long id;
    private String code;
    private String name;
    private String purpose;
    private String draftContent;
    private Long currentRevisionId;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
