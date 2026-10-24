package com.shen.hospital.knowledge;

import com.shen.hospital.auth.domain.Role;
import com.shen.hospital.auth.service.AuthService;
import com.shen.hospital.common.BusinessTime;
import com.shen.hospital.knowledge.domain.KnowledgeDocument;
import com.shen.hospital.knowledge.mapper.KnowledgeDocumentMapper;
import com.shen.hospital.knowledge.service.KnowledgeTaskPersistence;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"hospital.jwt.secret=test-only-signing-key-at-least-32-bytes-long","hospital.bootstrap.enabled=false"})
@EnabledIfEnvironmentVariable(named="MYSQL_PASSWORD",matches=".+")
@Transactional(transactionManager="mysqlTransactionManager")
class KnowledgeMaintenanceIntegrationTest {
    @Autowired KnowledgeDocumentMapper documents;
    @Autowired KnowledgeTaskPersistence persistence;
    @Autowired AuthService auth;
    @Autowired BusinessTime time;
    KnowledgeDocument doc;

    @BeforeEach void init() {
        var admin=auth.createUser("kb_"+UUID.randomUUID().toString().substring(0,8),"TestPassword123!","知识库工程测试（虚构）",Role.ADMIN);
        doc=new KnowledgeDocument();doc.setTitle("维护工程测试（虚构）");doc.setFileKey(UUID.randomUUID()+".txt");
        doc.setFileSha256("0".repeat(64));doc.setMimeType("text/plain");doc.setFileSize(12L);
        doc.setSourceDescription("仅数据库事务验证");doc.setDocumentVersion(UUID.randomUUID().toString());doc.setUploadedBy(admin.getId());
        doc.setEnabled(1);doc.setStatus("READY");doc.setTaskKind("INGEST");doc.setTargetSpaceKey("old-space");
        doc.setTotalChunks(1);doc.setEmbeddedChunks(1);doc.setAttemptNo(0);documents.insert(doc);
    }
    void publish(String operation) {
        assertEquals(1,documents.claim(doc.getId(),operation,time.now().plusMinutes(10),"new-space"));
        assertEquals(1,documents.progress(doc.getId(),operation,"EMBEDDING",1,1));
        assertEquals(1,documents.publish(doc.getId(),operation,"new-space",1));
    }
    @Test void fullRebuildMarkersCommitTogetherAndPreserveEnablementOnPublication() {
        assertTrue(persistence.queueRebuild("new-space").contains(doc.getId()));
        var queued=documents.selectById(doc.getId());assertEquals("REBUILD",queued.getTaskKind());assertEquals("QUEUED",queued.getStatus());
        assertEquals("new-space",queued.getTargetSpaceKey());assertEquals(1,queued.getEnabled());
        publish("rebuild-op");var ready=documents.selectById(doc.getId());
        assertEquals("READY",ready.getStatus());assertEquals("REBUILD",ready.getTaskKind());assertEquals(1,ready.getEnabled());
        persistence.finishRebuild();assertEquals("INGEST",documents.selectById(doc.getId()).getTaskKind());
    }
    @Test void disabledOldSpaceDocumentReusesIdentityAndCannotBecomeEnabledByPublication() {
        assertEquals(0,documents.queueDisabledRebuild(doc.getId(),"new-space"));
        documents.setEnabled(doc.getId(),0);assertEquals(1,documents.queueDisabledRebuild(doc.getId(),"new-space"));
        publish("single-op");assertEquals(0,documents.selectById(doc.getId()).getEnabled());
        assertEquals("INGEST",documents.selectById(doc.getId()).getTaskKind());
    }
    @Test void interruptedAndFailedRebuildRetainsMaintenanceMarker() {
        persistence.queueRebuild("new-space");documents.failInterrupted();
        var failed=documents.selectById(doc.getId());assertEquals("FAILED",failed.getStatus());assertEquals("REBUILD",failed.getTaskKind());
        assertEquals(1,documents.retry(doc.getId()));assertEquals("REBUILD",documents.selectById(doc.getId()).getTaskKind());
        documents.claim(doc.getId(),"new-op",time.now().plusMinutes(10),"new-space");
        assertEquals(0,documents.publish(doc.getId(),"old-op","old-space",1));
    }
}
