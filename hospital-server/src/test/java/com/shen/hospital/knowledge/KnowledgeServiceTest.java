package com.shen.hospital.knowledge;

import com.shen.hospital.ai.*;
import com.shen.hospital.common.*;
import com.shen.hospital.knowledge.domain.*;
import com.shen.hospital.knowledge.mapper.*;
import com.shen.hospital.knowledge.repository.VectorRepository;
import com.shen.hospital.knowledge.service.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeServiceTest {
    @TempDir java.nio.file.Path directory;
    KnowledgeDocumentMapper documents=mock(KnowledgeDocumentMapper.class);
    KnowledgeChunkMapper chunks=mock(KnowledgeChunkMapper.class);
    VectorRepository vectors=mock(VectorRepository.class);
    HospitalAiService ai=mock(HospitalAiService.class);
    ThreadPoolTaskExecutor executor=mock(ThreadPoolTaskExecutor.class);
    KnowledgeService service;
    com.shen.hospital.ai.agent.AgentRunMapper runs=mock(com.shen.hospital.ai.agent.AgentRunMapper.class);
    KnowledgeTaskPersistence persistence=mock(KnowledgeTaskPersistence.class);
    KnowledgeDocument doc;
    @BeforeEach void init(){
        var models=new ModelFactory(new AiProperties("http://localhost/v1","test-key","qwen-plus","text-embedding-v4",3,30));
        service=new KnowledgeService(documents,chunks,vectors,new KnowledgeParser(),persistence,ai,models,
                new BusinessTime(Clock.system(ZoneId.of("Asia/Shanghai"))),executor,directory.toString(),1024,runs);
        doc=new KnowledgeDocument();doc.setId(7L);doc.setTitle("工程测试");doc.setFileSize(10L);doc.setStatus("READY");
        doc.setTotalChunks(1);doc.setTargetSpaceKey(service.spaceKey());when(documents.selectById(7L)).thenReturn(doc);
    }
    @Test void duplicateUploadCreatesNeitherFilesNorModelCalls()throws Exception{
        when(documents.findDuplicate(anyString(),eq("v1"))).thenReturn(doc);
        var result=service.upload(1L,new MockMultipartFile("file","guide.txt","text/plain","test".getBytes()),"title","source","v1","trace");
        assertTrue(result.duplicate());try(var files=java.nio.file.Files.list(directory)){assertEquals(0,files.count());}
        verifyNoInteractions(executor,ai);verify(documents,never()).insert(any(KnowledgeDocument.class));
    }
    @Test void enableRequiresAllStoredVectorsReadyInCurrentSpace(){
        var chunk=new KnowledgeChunk();chunk.setId(9L);
        when(chunks.listByDocument(7L,KnowledgeParser.SPLIT_VERSION)).thenReturn(List.of(chunk));
        when(vectors.verifyReady(List.of(9L),service.spaceKey(),3)).thenReturn(false);
        assertEquals("KNOWLEDGE_INCONSISTENT",assertThrows(BusinessException.class,() -> service.enabled(7L,true)).code());
        verify(documents,never()).setEnabled(anyLong(),anyInt());
    }
    @Test void noEnabledKnowledgeDoesNotStartQueryEmbedding(){
        when(documents.candidateChunkIds(anyString())).thenReturn(List.of());
        assertEquals("KNOWLEDGE_UNAVAILABLE",assertThrows(BusinessException.class,() -> service.search("咳嗽",5,"trace")).code());
        verifyNoInteractions(ai,vectors);
    }
    @Test void interruptedRebuildRemainsMaintenanceWhileIngestFailuresDoNot(){
        doc.setTaskKind("REBUILD");doc.setStatus("FAILED");
        when(documents.rebuilding()).thenReturn(List.of(doc));
        service.recoverInterrupted();
        assertEquals("MAINTENANCE",service.status().status());assertEquals(1,service.status().rebuildFailed());
        when(documents.rebuilding()).thenReturn(List.of());
        assertEquals("UNAVAILABLE",service.status().status());
    }
    @Test void maintenanceRejectsSearchUploadAndEnableWithoutPaidCalls(){
        when(documents.rebuilding()).thenReturn(List.of(doc));
        assertEquals("KNOWLEDGE_MAINTENANCE",assertThrows(BusinessException.class,()->service.search("咳嗽",5,"trace")).code());
        assertEquals("KNOWLEDGE_MAINTENANCE",assertThrows(BusinessException.class,()->service.enabled(7L,false)).code());
        assertEquals("KNOWLEDGE_MAINTENANCE",assertThrows(BusinessException.class,()->service.upload(1L,
                new MockMultipartFile("file","guide.txt","text/plain","test".getBytes()),"title","source","v1","trace")).code());
        verifyNoInteractions(ai,executor,vectors);
    }
    @Test void activeAgentOrIngestRejectsRebuildBeforeMarkingAnyDocument(){
        when(runs.interrupted()).thenReturn(List.of(new com.shen.hospital.ai.agent.AgentRun()));
        assertEquals("AGENT_ACTIVE",assertThrows(BusinessException.class,()->service.rebuild("trace")).code());
        when(runs.interrupted()).thenReturn(List.of());when(documents.activeCount()).thenReturn(1);
        assertEquals("KNOWLEDGE_BUSY",assertThrows(BusinessException.class,()->service.rebuild("trace")).code());
        verifyNoInteractions(persistence,executor,ai);
    }
    @Test void rebuildPersistsEveryTargetBeforeQueuingOneWorker(){
        when(documents.allDocuments()).thenReturn(List.of(doc));
        when(persistence.queueRebuild(service.spaceKey())).thenReturn(List.of(7L,8L));
        service.rebuild("trace");
        var order=inOrder(persistence,executor);order.verify(persistence).queueRebuild(service.spaceKey());order.verify(executor).execute(any(Runnable.class));
        verifyNoInteractions(ai,vectors);
    }
    @Test void rejectedRebuildQueueMarksAllTargetsFailed(){
        when(documents.allDocuments()).thenReturn(List.of(doc));when(persistence.queueRebuild(service.spaceKey())).thenReturn(List.of(7L,8L));
        doThrow(new org.springframework.core.task.TaskRejectedException("test")).when(executor).execute(any(Runnable.class));
        service.rebuild("trace");verify(documents).failQueued(eq(7L),eq("TASK_QUEUE_FULL"),anyString());verify(documents).failQueued(eq(8L),eq("TASK_QUEUE_FULL"),anyString());
    }
    @Test void onlyFailedRebuildTargetsInCurrentSpaceCanRetryDuringMaintenance(){
        doc.setTaskKind("INGEST");when(documents.rebuilding()).thenReturn(List.of(doc));
        assertEquals("KNOWLEDGE_MAINTENANCE",assertThrows(BusinessException.class,()->service.retry(7L,"trace")).code());
        doc.setTaskKind("REBUILD");doc.setTargetSpaceKey("old-space");
        assertEquals("KNOWLEDGE_MAINTENANCE",assertThrows(BusinessException.class,()->service.retry(7L,"trace")).code());
        doc.setTargetSpaceKey(service.spaceKey());when(documents.retry(7L)).thenReturn(1);
        service.retry(7L,"trace");verify(executor).execute(any(Runnable.class));
    }
    @Test void admissionAndRebuildAreAtomicAtTheSharedGate()throws Exception{
        when(documents.allDocuments()).thenReturn(List.of(doc));when(persistence.queueRebuild(anyString())).thenReturn(List.of(7L));
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var admission=pool.submit(()->service.admitAgent(()->{entered.countDown();try{release.await(3,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException ex){throw new RuntimeException(ex);}return true;}));
            assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
            var rebuild=pool.submit(()->service.rebuild("trace"));
            assertThrows(java.util.concurrent.TimeoutException.class,()->rebuild.get(100,java.util.concurrent.TimeUnit.MILLISECONDS));
            verify(persistence,never()).queueRebuild(anyString());release.countDown();
            assertTrue(admission.get(2,java.util.concurrent.TimeUnit.SECONDS));rebuild.get(2,java.util.concurrent.TimeUnit.SECONDS);
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void rebuildOpensOnlyAfterEveryTargetHasMatchingReadyVectors()throws Exception{
        var second=new KnowledgeDocument();second.setId(8L);second.setTitle("虚构资料二");second.setFileSize(10L);second.setTotalChunks(1);second.setEnabled(0);
        doc.setEnabled(1);
        var targets=List.of(doc,second);for(var target:targets){target.setTaskKind("REBUILD");target.setStatus("QUEUED");target.setTargetSpaceKey(service.spaceKey());stubProcess(target);}
        when(documents.rebuilding()).thenAnswer(inv->targets.stream().filter(d->"REBUILD".equals(d.getTaskKind())).toList());
        doAnswer(inv->{targets.forEach(d->d.setTaskKind("INGEST"));return null;}).when(persistence).finishRebuild();
        service.process(7L,"trace");assertEquals("MAINTENANCE",service.status().status());assertEquals(1,service.status().rebuildReady());
        verify(persistence,never()).finishRebuild();assertEquals(1,doc.getEnabled());
        service.process(8L,"trace");assertNotEquals("MAINTENANCE",service.status().status());verify(persistence).finishRebuild();
        verify(vectors,atLeastOnce()).verifyReady(List.of(107L),service.spaceKey(),3);
        verify(vectors,atLeastOnce()).verifyReady(List.of(108L),service.spaceKey(),3);
        assertEquals(1,doc.getEnabled());assertEquals(0,second.getEnabled());
    }
    @Test void failedOrLateEmbeddingNeverPublishesOrReleasesMaintenance()throws Exception{
        doc.setTaskKind("REBUILD");doc.setStatus("QUEUED");stubProcess(doc);when(documents.rebuilding()).thenReturn(List.of(doc));
        when(ai.embedDocuments(anyList(),any())).thenAnswer(inv->{doc.setTaskDeadline(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(1));return List.of(new float[]{1,0,0});});
        service.process(7L,"trace");assertEquals("FAILED",doc.getStatus());assertEquals("MAINTENANCE",service.status().status());
        verify(vectors,never()).upsertBatch(anyString(),anyList());verify(documents,never()).publish(anyLong(),anyString(),anyString(),anyInt());verify(persistence,never()).finishRebuild();
    }
    @Test void vectorFailureKeepsRebuildUnavailable()throws Exception{
        doc.setTaskKind("REBUILD");doc.setStatus("QUEUED");stubProcess(doc);when(documents.rebuilding()).thenReturn(List.of(doc));
        doThrow(new IllegalStateException("test vector outage")).when(vectors).upsertBatch(anyString(),anyList());
        service.process(7L,"trace");assertEquals("FAILED",doc.getStatus());assertEquals("MAINTENANCE",service.status().status());
        verify(documents,never()).publish(anyLong(),anyString(),anyString(),anyInt());verify(persistence,never()).finishRebuild();
    }
    void stubProcess(KnowledgeDocument target)throws Exception{
        long id=target.getId();byte[] bytes="仅工程测试的虚构就医资料".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        target.setFileKey(id+".txt");java.nio.file.Files.write(directory.resolve(target.getFileKey()),bytes);
        target.setFileSha256(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        when(documents.selectById(id)).thenReturn(target);
        when(documents.claim(eq(id),anyString(),any(),anyString())).thenAnswer(inv->{target.setTaskOperationId(inv.getArgument(1));target.setTaskDeadline(inv.getArgument(2));target.setTargetSpaceKey(inv.getArgument(3));target.setStatus("PARSING");return 1;});
        var chunk=new KnowledgeChunk();chunk.setId(100+id);chunk.setContent(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        when(persistence.createOrReuseChunks(eq(id),anyList())).thenReturn(List.of(chunk));when(chunks.listByDocument(id,KnowledgeParser.SPLIT_VERSION)).thenReturn(List.of(chunk));
        when(documents.progress(eq(id),anyString(),anyString(),anyInt(),anyInt())).thenAnswer(inv->{target.setStatus(inv.getArgument(2));return 1;});
        when(ai.embedDocuments(anyList(),any())).thenReturn(List.of(new float[]{1,0,0}));when(vectors.verifyReady(anyList(),eq(service.spaceKey()),eq(3))).thenReturn(true);
        when(documents.publish(eq(id),anyString(),anyString(),anyInt())).thenAnswer(inv->{target.setStatus("READY");target.setTaskOperationId(null);return 1;});
        when(documents.fail(eq(id),anyString(),anyString(),anyString())).thenAnswer(inv->{target.setStatus("FAILED");target.setTaskOperationId(null);return 1;});
    }
}
