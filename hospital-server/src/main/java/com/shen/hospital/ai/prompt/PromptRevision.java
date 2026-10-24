package com.shen.hospital.ai.prompt;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Data;

@Data @TableName("prompt_revision")
public class PromptRevision {
    @TableId(type = IdType.AUTO) private Long id;
    private Long templateId;
    private Integer revision;
    private String content;
    private String contentHash;
    private String changeNote;
    private Long publishedBy;
    private LocalDateTime publishedAt;
}
