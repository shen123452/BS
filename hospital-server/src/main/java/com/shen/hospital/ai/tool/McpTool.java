package com.shen.hospital.ai.tool;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Data;

@Data @TableName("mcp_tool")
public class McpTool {
    @TableId(type = IdType.AUTO) private Long id;
    private String code;
    private String name;
    private String description;
    private String inputSchema;
    private String executorCode;
    private String status;
    private Integer timeoutMs;
    private Integer maxResultChars;
    private Integer sortNo;
    private Integer revision;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
