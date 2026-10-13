package com.shen.hospital.knowledge.dto;

import com.shen.hospital.knowledge.domain.KnowledgeChunk;
public record KnowledgeChunkView(String id, int chunkNo, String content, String titlePath,
        Integer pageStart, Integer pageEnd, Integer paragraphStart, Integer paragraphEnd, int charCount) {
    public static KnowledgeChunkView of(KnowledgeChunk c) { return new KnowledgeChunkView(c.getId().toString(), c.getChunkNo(), c.getContent(), c.getTitlePath(), c.getPageStart(), c.getPageEnd(), c.getParagraphStart(), c.getParagraphEnd(), c.getCharCount()); }
}
