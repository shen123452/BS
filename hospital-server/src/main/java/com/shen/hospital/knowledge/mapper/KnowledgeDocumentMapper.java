package com.shen.hospital.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shen.hospital.knowledge.domain.KnowledgeDocument;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {
    @Select("SELECT * FROM knowledge_document WHERE id=#{id} FOR UPDATE")
    KnowledgeDocument lockById(Long id);

    @Select("SELECT * FROM knowledge_document WHERE file_sha256=#{sha256} AND document_version=#{version} LIMIT 1")
    KnowledgeDocument findDuplicate(@Param("sha256") String sha256, @Param("version") String version);

    @Select("SELECT c.id FROM knowledge_document d JOIN knowledge_chunk c ON c.document_id=d.id WHERE d.enabled=1 AND d.status='READY' AND d.target_space_key=#{spaceKey} ORDER BY c.id LIMIT 5001")
    List<Long> candidateChunkIds(String spaceKey);

    @Select("SELECT COUNT(*) FROM knowledge_chunk")
    long countAllChunks();

    @Select("SELECT * FROM knowledge_document WHERE status='QUEUED' ORDER BY id LIMIT 64")
    List<KnowledgeDocument> queued();

    @Select("SELECT * FROM knowledge_document ORDER BY id")
    List<KnowledgeDocument> allDocuments();

    @Select("SELECT * FROM knowledge_document WHERE task_kind='REBUILD' ORDER BY id")
    List<KnowledgeDocument> rebuilding();

    @Select("SELECT COUNT(*) FROM knowledge_document WHERE status IN ('QUEUED','PARSING','CHUNKING','EMBEDDING')")
    int activeCount();

    @Update("UPDATE knowledge_document SET task_kind='REBUILD',status='QUEUED',target_space_key=#{spaceKey},embedded_chunks=0,task_operation_id=NULL,task_deadline=NULL,error_code=NULL,error_message=NULL")
    int queueRebuild(String spaceKey);

    @Update("UPDATE knowledge_document SET task_kind='INGEST' WHERE task_kind='REBUILD'")
    int finishRebuild();

    @Update("UPDATE knowledge_document SET status='QUEUED',target_space_key=#{spaceKey},embedded_chunks=0,error_code=NULL,error_message=NULL WHERE id=#{id} AND enabled=0 AND status IN ('READY','FAILED') AND task_kind='INGEST'")
    int queueDisabledRebuild(@Param("id") Long id,@Param("spaceKey") String spaceKey);

    @Update("UPDATE knowledge_document SET status='PARSING', task_operation_id=#{operationId}, task_deadline=#{deadline}, target_space_key=#{spaceKey}, attempt_no=attempt_no+1, error_code=NULL, error_message=NULL WHERE id=#{id} AND status='QUEUED'")
    int claim(@Param("id") Long id, @Param("operationId") String operationId,
              @Param("deadline") LocalDateTime deadline, @Param("spaceKey") String spaceKey);

    @Update("UPDATE knowledge_document SET status=#{status}, total_chunks=#{total}, embedded_chunks=#{embedded} WHERE id=#{id} AND task_operation_id=#{operationId}")
    int progress(@Param("id") Long id, @Param("operationId") String operationId, @Param("status") String status,
                 @Param("total") int total, @Param("embedded") int embedded);

    @Update("UPDATE knowledge_document SET status='READY', target_space_key=#{spaceKey}, total_chunks=#{total}, embedded_chunks=#{total}, enabled=IF(task_kind='REBUILD',enabled,0), task_operation_id=NULL, task_deadline=NULL, error_code=NULL, error_message=NULL WHERE id=#{id} AND task_operation_id=#{operationId} AND status='EMBEDDING'")
    int publish(@Param("id") Long id, @Param("operationId") String operationId, @Param("spaceKey") String spaceKey, @Param("total") int total);

    @Update("UPDATE knowledge_document SET status='FAILED', task_operation_id=NULL, task_deadline=NULL, error_code=#{code}, error_message=#{message} WHERE id=#{id} AND task_operation_id=#{operationId}")
    int fail(@Param("id") Long id, @Param("operationId") String operationId, @Param("code") String code, @Param("message") String message);

    @Update("UPDATE knowledge_document SET status='FAILED', task_operation_id=NULL, task_deadline=NULL, error_code='TASK_INTERRUPTED', error_message='上次处理未完成，请重试' WHERE status IN ('QUEUED','PARSING','CHUNKING','EMBEDDING')")
    int failInterrupted();

    @Update("UPDATE knowledge_document SET status='QUEUED', error_code=NULL, error_message=NULL WHERE id=#{id} AND status='FAILED'")
    int retry(@Param("id") Long id);

    @Update("UPDATE knowledge_document SET status='FAILED',error_code=#{code},error_message=#{message} WHERE id=#{id} AND status='QUEUED'")
    int failQueued(@Param("id") Long id, @Param("code") String code, @Param("message") String message);

    @Update("UPDATE knowledge_document SET enabled=#{enabled} WHERE id=#{id}")
    int setEnabled(@Param("id") Long id, @Param("enabled") int enabled);
}
