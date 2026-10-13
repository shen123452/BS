package com.shen.hospital.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shen.hospital.knowledge.domain.KnowledgeChunk;
import com.shen.hospital.knowledge.domain.KnowledgeHitRow;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {
    @Select("SELECT * FROM knowledge_chunk WHERE document_id=#{documentId} AND split_version=#{splitVersion} ORDER BY chunk_no")
    List<KnowledgeChunk> listByDocument(@Param("documentId") Long documentId, @Param("splitVersion") String splitVersion);

    @Select("SELECT COUNT(*) FROM knowledge_chunk WHERE document_id=#{documentId}")
    long countByDocument(Long documentId);

    @Select("<script>SELECT c.id,c.content,c.page_start,c.page_end,c.paragraph_start,c.paragraph_end,d.title,d.source_description,d.document_version FROM knowledge_chunk c JOIN knowledge_document d ON d.id=c.document_id WHERE c.id IN <foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<KnowledgeHitRow> findHits(@Param("ids") List<Long> ids);
}
