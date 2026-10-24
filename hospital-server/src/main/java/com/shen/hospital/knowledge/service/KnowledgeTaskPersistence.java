package com.shen.hospital.knowledge.service;

import com.shen.hospital.common.BusinessException;
import com.shen.hospital.knowledge.domain.KnowledgeChunk;
import com.shen.hospital.knowledge.mapper.KnowledgeChunkMapper;
import com.shen.hospital.knowledge.mapper.KnowledgeDocumentMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeTaskPersistence {
    private final KnowledgeDocumentMapper documents;
    private final KnowledgeChunkMapper chunks;

    public KnowledgeTaskPersistence(KnowledgeDocumentMapper documents, KnowledgeChunkMapper chunks) {
        this.documents = documents; this.chunks = chunks;
    }

    // All targets become durable maintenance markers in one MySQL transaction, before any vector is overwritten.
    @Transactional(transactionManager = "mysqlTransactionManager")
    public List<Long> queueRebuild(String spaceKey) {
        documents.queueRebuild(spaceKey);
        return documents.rebuilding().stream().map(d -> d.getId()).toList();
    }

    @Transactional(transactionManager = "mysqlTransactionManager")
    public void finishRebuild() { documents.finishRebuild(); }

    @Transactional(transactionManager = "mysqlTransactionManager")
    public List<KnowledgeChunk> createOrReuseChunks(Long documentId, List<KnowledgeParser.Draft> drafts) {
        var existing = chunks.listByDocument(documentId, KnowledgeParser.SPLIT_VERSION);
        if (!existing.isEmpty()) {
            if (existing.size() != drafts.size()) throw inconsistent();
            for (int i = 0; i < existing.size(); i++) {
                String expected = hash(drafts.get(i).content());
                if (!expected.equals(existing.get(i).getContentSha256())) throw inconsistent();
            }
            return existing;
        }
        if (documents.countAllChunks() + drafts.size() > 5000)
            throw new BusinessException(HttpStatus.CONFLICT, "KNOWLEDGE_TOO_LARGE", "知识片段总量超过首版5000片段范围");
        var inserted = new ArrayList<KnowledgeChunk>(drafts.size());
        int no = 0;
        for (var draft : drafts) {
            var chunk = new KnowledgeChunk(); chunk.setDocumentId(documentId); chunk.setChunkNo(++no);
            chunk.setSplitVersion(KnowledgeParser.SPLIT_VERSION); chunk.setContent(draft.content()); chunk.setContentSha256(hash(draft.content()));
            chunk.setTitlePath(draft.titlePath()); chunk.setPageStart(draft.pageStart()); chunk.setPageEnd(draft.pageEnd());
            chunk.setParagraphStart(draft.paragraphStart()); chunk.setParagraphEnd(draft.paragraphEnd());
            chunk.setCharCount(draft.content().codePointCount(0, draft.content().length())); chunks.insert(chunk); inserted.add(chunk);
        }
        return inserted;
    }

    private static String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private static BusinessException inconsistent() {
        return new BusinessException(HttpStatus.CONFLICT, "KNOWLEDGE_CHUNKS_INCONSISTENT", "已保存片段与源资料不一致，请联系管理员检查");
    }
}
