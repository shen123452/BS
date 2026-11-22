package com.glucose.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("kb_chunk")
public class KbChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long docId;
    private Integer documentVersion;
    private String indexVersion;
    private Integer chunkNo;
    private String content;
    /** float32 小端字节序 */
    private byte[] embedding;
    private String embeddingModel;
    private Integer dim;
    private String contentHash;
    private LocalDateTime createdAt;
}
