package com.shen.hospital.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shen.hospital.ai.HospitalAiService;
import com.shen.hospital.ai.ModelFactory;
import com.shen.hospital.common.*;
import com.shen.hospital.knowledge.domain.*;
import com.shen.hospital.knowledge.dto.*;
import com.shen.hospital.knowledge.mapper.*;
import com.shen.hospital.knowledge.repository.VectorRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.springframework.beans.factory.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;

@Service
public class KnowledgeService {
    private final KnowledgeDocumentMapper documents;
    private final KnowledgeChunkMapper chunks;
    private final VectorRepository vectors;
    private final KnowledgeParser parser;
    private final KnowledgeTaskPersistence persistence;
    private final HospitalAiService ai;
    private final ModelFactory models;
    private final BusinessTime time;
    private final ThreadPoolTaskExecutor executor;
    private final Path storage;
    private final long maxFileSize;
    private final com.shen.hospital.ai.agent.AgentRunMapper runs;
    // 单机任务写入/重试共用互斥；外部模型调用不持有此锁。
    private final Object taskMutex = new Object();
    private final ReentrantReadWriteLock knowledgeGate = new ReentrantReadWriteLock(true);

    public KnowledgeService(KnowledgeDocumentMapper documents, KnowledgeChunkMapper chunks, VectorRepository vectors,
            KnowledgeParser parser, KnowledgeTaskPersistence persistence, HospitalAiService ai, ModelFactory models,
            BusinessTime time, @Qualifier("knowledgeTaskExecutor") ThreadPoolTaskExecutor executor,
            @Value("${hospital.knowledge.storage-path:./data/knowledge}") String storagePath,
            @Value("${hospital.knowledge.max-file-size:10485760}") long maxFileSize,
            com.shen.hospital.ai.agent.AgentRunMapper runs) {
        this.documents=documents; this.chunks=chunks; this.vectors=vectors; this.parser=parser; this.persistence=persistence;
        this.ai=ai; this.models=models; this.time=time; this.executor=executor;
        this.storage=Path.of(storagePath).toAbsolutePath().normalize(); this.maxFileSize=maxFileSize; this.runs=runs;
    }

    public record UploadResult(KnowledgeDocumentView document, boolean duplicate) {}
    public record SearchResult(List<KnowledgeHit> hits, String spaceKey, long durationMs) {}
    public record KnowledgeStatus(String status, String spaceKey, int candidateCount,
                                  int rebuildTotal,int rebuildReady,int rebuildFailed) {
        public KnowledgeStatus(String status,String spaceKey,int candidateCount) { this(status,spaceKey,candidateCount,0,0,0); }
    }

    // Admission and maintenance share this lock until the new run's MySQL transaction has committed.
    public <T> T admitAgent(java.util.function.Supplier<T> admission) {
        knowledgeGate.readLock().lock();
        try { return admission.get(); } finally { knowledgeGate.readLock().unlock(); }
    }
    public boolean maintenance() { return !documents.rebuilding().isEmpty(); }
    private void requireWritable() { if(maintenance()) throw maintaining(); }

    public void recoverInterrupted() { synchronized(taskMutex) { documents.failInterrupted(); } }

    public UploadResult upload(Long adminId, MultipartFile file, String title, String source, String version, String traceId) {
        requireText(title,200,"标题"); requireText(source,1000,"来源说明"); requireText(version,100,"资料版本");
        if(file==null || file.isEmpty() || file.getSize()>maxFileSize) throw invalid("资料超过大小限制或为空");
        String original=Objects.requireNonNullElse(file.getOriginalFilename(),"").toLowerCase(Locale.ROOT);
        if(!original.endsWith(".txt") && !original.endsWith(".pdf")) throw invalid("仅支持UTF-8 TXT或文本型PDF");
        byte[] bytes;
        try { bytes=file.getBytes(); } catch(IOException ex) { throw storageError(); }
        if(bytes.length==0 || bytes.length>maxFileSize) throw invalid("资料超过大小限制或为空");
        String sha=hash(bytes);
        synchronized(taskMutex) {
            requireWritable();
            var previous=documents.findDuplicate(sha,version.strip());
            if(previous!=null) return new UploadResult(KnowledgeDocumentView.of(previous),true);
            String key=UUID.randomUUID()+(original.endsWith(".pdf")?".pdf":".txt");
            Path path=storage.resolve(key);
            try { Files.createDirectories(storage); Files.write(path,bytes,StandardOpenOption.CREATE_NEW); }
            catch(IOException ex) { throw storageError(); }
            var doc=new KnowledgeDocument();
            doc.setTitle(title.strip()); doc.setSourceDescription(source.strip()); doc.setDocumentVersion(version.strip());
            doc.setFileKey(key); doc.setFileSha256(sha); doc.setFileSize((long)bytes.length);
            doc.setMimeType(key.endsWith(".pdf")?"application/pdf":"text/plain");
            doc.setEnabled(0); doc.setStatus("QUEUED"); doc.setTaskKind("INGEST");
            doc.setTotalChunks(0); doc.setEmbeddedChunks(0); doc.setAttemptNo(0); doc.setUploadedBy(adminId);
            try { documents.insert(doc); }
            catch(RuntimeException ex) { try { Files.deleteIfExists(path); } catch(IOException ignored) {} throw ex; }
            submit(doc.getId(),traceId);
            return new UploadResult(KnowledgeDocumentView.of(documents.selectById(doc.getId())),false);
        }
    }

    private void submit(Long id,String traceId) {
        try { executor.execute(()->process(id,traceId)); }
        catch(org.springframework.core.task.TaskRejectedException ex) {
            documents.failQueued(id,"TASK_QUEUE_FULL","处理队列已满，请稍后重试");
        }
    }

    public PageResult<KnowledgeDocumentView> list(long page,long size) {
        return PageResult.from(documents.selectPage(new Page<>(page,size),new LambdaQueryWrapper<KnowledgeDocument>()
                .orderByDesc(KnowledgeDocument::getId)),KnowledgeDocumentView::of);
    }
    public KnowledgeDocumentView detail(Long id) { return KnowledgeDocumentView.of(requireDocument(id)); }
    public PageResult<KnowledgeChunkView> chunkList(Long id,long page,long size) {
        requireDocument(id);
        return PageResult.from(chunks.selectPage(new Page<>(page,size),new LambdaQueryWrapper<KnowledgeChunk>()
                .eq(KnowledgeChunk::getDocumentId,id).orderByAsc(KnowledgeChunk::getChunkNo)),KnowledgeChunkView::of);
    }

    public void process(Long id,String traceId) {
        String operation=UUID.randomUUID().toString();
        String space=spaceKey();
        try {
            KnowledgeDocument doc;
            synchronized(taskMutex) {
                doc=requireDocument(id);
                if(!"QUEUED".equals(doc.getStatus())) return;
                if(documents.claim(id,operation,time.now().plusMinutes(10),space)!=1) return;
            }
            Path file=storage.resolve(doc.getFileKey()).normalize();
            if(!file.startsWith(storage)) throw invalid("资料存储路径异常");
            byte[] bytes=Files.readAllBytes(file);
            if(!hash(bytes).equals(doc.getFileSha256())) throw invalid("资料校验失败，请上传新版本");
            var drafts=parser.parse(doc.getFileKey(),bytes);
            List<KnowledgeChunk> stored;
            synchronized(taskMutex) {
                requireOperation(id,operation);
                stored=persistence.createOrReuseChunks(id,drafts);
                progress(id,operation,"EMBEDDING",stored.size(),0);
            }
            var ids=stored.stream().map(KnowledgeChunk::getId).toList();
            for(int start=0;start<stored.size();start+=16) {
                synchronized(taskMutex) { requireOperation(id,operation); }
                var batch=stored.subList(start,Math.min(start+16,stored.size()));
                var embedded=ai.embedDocuments(batch.stream().map(KnowledgeChunk::getContent).toList(),traceId);
                var inputs=new ArrayList<VectorRepository.VectorInput>();
                for(int i=0;i<batch.size();i++) inputs.add(new VectorRepository.VectorInput(batch.get(i).getId(),embedded.get(i)));
                synchronized(taskMutex) {
                    requireOperation(id,operation);
                    vectors.upsertBatch(space,inputs);
                    progress(id,operation,"EMBEDDING",stored.size(),start+batch.size());
                }
            }
            synchronized(taskMutex) {
                requireOperation(id,operation);
                if(!vectors.verifyReady(ids,space,models.config().embeddingDimension())) throw inconsistent();
                knowledgeGate.writeLock().lock();
                try {
                    requireOperation(id,operation);
                    if(documents.publish(id,operation,space,ids.size())!=1) throw inconsistent();
                    finishMaintenanceIfReady();
                } finally { knowledgeGate.writeLock().unlock(); }
            }
        } catch(Exception ex) {
            synchronized(taskMutex) {
                String code=ex instanceof BusinessException be?be.code():"INGEST_FAILED";
                String message=ex instanceof BusinessException be?be.getMessage():"资料处理失败，请检查模型配置、文件和向量库后重试";
                documents.fail(id,operation,code,message);
            }
        }
    }

    private void requireOperation(Long id,String operation) {
        var doc=requireDocument(id);
        if(!operation.equals(doc.getTaskOperationId()) || !List.of("PARSING","CHUNKING","EMBEDDING").contains(doc.getStatus()))
            throw BusinessException.conflict("TASK_CHANGED","处理任务已终结");
        if(doc.getTaskDeadline()==null || !doc.getTaskDeadline().isAfter(time.now()))
            throw BusinessException.conflict("TASK_TIMEOUT","资料处理超时，请稍后重试");
    }
    private void progress(Long id,String op,String state,int total,int embedded) {
        if(documents.progress(id,op,state,total,embedded)!=1) throw inconsistent();
    }

    public KnowledgeDocumentView retry(Long id,String traceId) {
        synchronized(taskMutex) {
            var doc=requireDocument(id);
            if(maintenance() && (!"REBUILD".equals(doc.getTaskKind()) || !spaceKey().equals(doc.getTargetSpaceKey())))
                throw maintaining();
            if(documents.retry(id)!=1) throw BusinessException.conflict("KNOWLEDGE_RETRY_UNAVAILABLE","只有失败资料可以重试");
            submit(id,traceId); return detail(id);
        }
    }

    public KnowledgeDocumentView enabled(Long id,boolean enabled) {
        synchronized(taskMutex) {
        knowledgeGate.writeLock().lock();
        try {
            requireWritable();
            var doc=requireDocument(id);
            if(enabled) {
                if(!"READY".equals(doc.getStatus()) || !spaceKey().equals(doc.getTargetSpaceKey()))
                    throw BusinessException.conflict("KNOWLEDGE_NOT_READY","资料尚未在当前向量空间完成校验");
                var ids=chunks.listByDocument(id,KnowledgeParser.SPLIT_VERSION).stream().map(KnowledgeChunk::getId).toList();
                if(ids.size()!=doc.getTotalChunks() || !vectors.verifyReady(ids,spaceKey(),models.config().embeddingDimension())) throw inconsistent();
            }
            documents.setEnabled(id,enabled?1:0); return detail(id);
        } finally { knowledgeGate.writeLock().unlock(); }
        }
    }

    public KnowledgeStatus rebuild(String traceId) {
        synchronized(taskMutex) {
            knowledgeGate.writeLock().lock();
            try {
                models.requireConfigured();
                if(!runs.interrupted().isEmpty()) throw BusinessException.conflict("AGENT_ACTIVE", "请先等待或停止正在运行的导诊，再重建知识库");
                if(documents.activeCount()>0) throw BusinessException.conflict("KNOWLEDGE_BUSY", "资料仍在处理中，请完成后再重建");
                if(documents.allDocuments().isEmpty()) throw BusinessException.conflict("KNOWLEDGE_EMPTY", "请先上传知识资料");
                var ids=persistence.queueRebuild(spaceKey());
                // One bounded executor task processes all targets, avoiding a partial enqueue when the queue is full.
                try { executor.execute(() -> ids.forEach(id -> process(id,traceId))); }
                catch(org.springframework.core.task.TaskRejectedException ex) {
                    ids.forEach(id -> documents.failQueued(id,"TASK_QUEUE_FULL","处理队列已满，请重新开始重建"));
                }
                return status();
            } finally { knowledgeGate.writeLock().unlock(); }
        }
    }

    public KnowledgeDocumentView rebuildDisabled(Long id,String traceId) {
        synchronized(taskMutex) {
            knowledgeGate.writeLock().lock();
            try {
                requireWritable(); models.requireConfigured(); requireDocument(id);
                if(documents.queueDisabledRebuild(id,spaceKey())!=1)
                    throw BusinessException.conflict("KNOWLEDGE_REBUILD_UNAVAILABLE","只有已停用且就绪或失败的资料可以单独重建");
                submit(id,traceId); return detail(id);
            } finally { knowledgeGate.writeLock().unlock(); }
        }
    }

    // REBUILD markers stay durable across failures and restarts. Clear them only after checking every target and vector.
    private void finishMaintenanceIfReady() {
        var targets=documents.rebuilding();
        if(targets.isEmpty() || targets.stream().anyMatch(d -> !"READY".equals(d.getStatus()) || !spaceKey().equals(d.getTargetSpaceKey()))) return;
        for(var doc:targets) {
            var ids=chunks.listByDocument(doc.getId(),KnowledgeParser.SPLIT_VERSION).stream().map(KnowledgeChunk::getId).toList();
            if(ids.isEmpty() || ids.size()!=doc.getTotalChunks() || !vectors.verifyReady(ids,spaceKey(),models.config().embeddingDimension())) throw inconsistent();
        }
        persistence.finishRebuild();
    }

    public KnowledgeStatus status() {
        knowledgeGate.readLock().lock();
        try {
            var targets=documents.rebuilding();
            if(!targets.isEmpty()) return new KnowledgeStatus("MAINTENANCE",spaceKey(),0,targets.size(),
                    (int)targets.stream().filter(d -> "READY".equals(d.getStatus()) && spaceKey().equals(d.getTargetSpaceKey())).count(),
                    (int)targets.stream().filter(d -> "FAILED".equals(d.getStatus())).count());
            int count=documents.candidateChunkIds(spaceKey()).size(); return new KnowledgeStatus(count==0?"UNAVAILABLE":"AVAILABLE",spaceKey(),count);
        }
        finally { knowledgeGate.readLock().unlock(); }
    }

    public SearchResult search(String query,int topK,String traceId) {
        requireText(query,1000,"检索内容");
        if(topK<1||topK>10) throw invalid("TopK须为1到10");
        long started=System.nanoTime(); String space=spaceKey();
        // 先检查是否有知识；生成查询向量时不占知识锁，然后在读锁内重新取候选。
        knowledgeGate.readLock().lock();
        try { requireWritable(); if(documents.candidateChunkIds(space).isEmpty()) throw unavailable(); }
        finally { knowledgeGate.readLock().unlock(); }
        var embedding=ai.embed(query.strip(),traceId);
        knowledgeGate.readLock().lock();
        try {
            requireWritable();
            var candidates=documents.candidateChunkIds(space);
            if(candidates.isEmpty()) throw unavailable();
            if(candidates.size()>5000) throw invalid("知识片段超过首版5000片段范围");
            if(!vectors.verifyReady(candidates,space,models.config().embeddingDimension())) throw inconsistent();
            var matches=vectors.search(candidates,space,embedding,topK);
            if(matches.isEmpty()) return new SearchResult(List.of(),space,elapsed(started));
            var rows=chunks.findHits(matches.stream().map(m->Long.valueOf(m.chunkId())).toList());
            var byId=new HashMap<Long,KnowledgeHitRow>(); rows.forEach(row->byId.put(row.getId(),row));
            var hits=new ArrayList<KnowledgeHit>();
            for(var match:matches) {
                var row=byId.get(Long.valueOf(match.chunkId()));
                if(row==null || !Double.isFinite(match.score())) throw inconsistent();
                hits.add(new KnowledgeHit(match.chunkId(),row.getTitle(),row.getSourceDescription(),row.getDocumentVersion(),
                        row.getContent(),row.getPageStart(),row.getPageEnd(),row.getParagraphStart(),row.getParagraphEnd(),match.score(),space));
            }
            return new SearchResult(List.copyOf(hits),space,elapsed(started));
        } finally { knowledgeGate.readLock().unlock(); }
    }

    public String spaceKey() {
        String identity=models.config().baseUrl().replaceAll("/+$","")+"|"+models.config().embeddingModel()+"|"+models.config().embeddingDimension()+"|langchain4j=1.0.1|norm=provider-v1";
        return "bailian/"+hash(identity.getBytes(StandardCharsets.UTF_8));
    }
    private KnowledgeDocument requireDocument(Long id) { var doc=documents.selectById(id); if(doc==null) throw BusinessException.missing("知识资料"); return doc; }
    private static void requireText(String value,int length,String field) { if(value==null||value.isBlank()||value.length()>length) throw invalid(field+"必填且不超过"+length+"字"); }
    private static long elapsed(long start) { return (System.nanoTime()-start)/1_000_000; }
    private static String hash(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch(Exception ex) { throw new IllegalStateException(ex); } }
    private static BusinessException invalid(String message) { return new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_DOCUMENT",message); }
    private static BusinessException unavailable() { return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"KNOWLEDGE_UNAVAILABLE","没有已启用且完成处理的资料，请先在知识库中准备资料"); }
    private static BusinessException inconsistent() { return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"KNOWLEDGE_INCONSISTENT","知识片段与向量索引不一致，请停用相关资料并重试"); }
    private static BusinessException storageError() { return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"KNOWLEDGE_STORAGE_UNAVAILABLE","资料存储暂不可用"); }
    private static BusinessException maintaining() { return BusinessException.conflict("KNOWLEDGE_MAINTENANCE","知识库正在重建，检索与资料修改暂停；失败资料可重试，普通预约仍可使用"); }
}
