package com.shen.hospital.knowledge.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data @TableName("knowledge_chunk")
public class KnowledgeChunk {
    @TableId(type = IdType.AUTO) private Long id;
    private Long documentId;
    private Integer chunkNo;
    private String splitVersion;
    private String content;
    private String contentSha256;
    private String titlePath;
    private Integer pageStart;
    private Integer pageEnd;
    private Integer paragraphStart;
    private Integer paragraphEnd;
    private Integer charCount;
    private LocalDateTime createdAt;
}
