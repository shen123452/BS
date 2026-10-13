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
    KnowledgeDocument doc;
    @BeforeEach void init(){
        var models=new ModelFactory(new AiProperties("http://localhost/v1","test-key","qwen-plus","text-embedding-v4",3,30));
        service=new KnowledgeService(documents,chunks,vectors,new KnowledgeParser(),mock(KnowledgeTaskPersistence.class),ai,models,
                new BusinessTime(Clock.system(ZoneId.of("Asia/Shanghai"))),executor,directory.toString(),1024);
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
}
