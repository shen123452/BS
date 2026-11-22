package com.glucose.knowledge.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.glucose.common.api.ErrorCode;
import com.glucose.common.exception.BusinessException;
import com.glucose.knowledge.entity.KbChunk;
import com.glucose.knowledge.entity.KbDocument;
import com.glucose.knowledge.mapper.KbChunkMapper;
import com.glucose.knowledge.mapper.KbDocumentMapper;
import com.glucose.system.service.AuditService;
import com.glucose.security.LoginContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 知识库服务:文档维护、按段落/标点切片(约 300–500 字、重叠约 50 字)、
 * 版本化索引(新版本全部成功后原子切换 active_index_version)、启停立即失效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbService {

    private static final int TARGET_CHUNK = 400;
    private static final int MAX_CHUNK = 500;
    private static final int OVERLAP = 50;

    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;
    private final EmbeddingService embeddingService;
    private final AuditService auditService;

    public record DocumentVO(String id, String title, String category, String sourceTitle, String sourceUrl,
                             String sourceVersion, String publishedOn, Integer documentVersion,
                             String contentHash, Integer status, String indexStatus,
                             String activeIndexVersion, Integer chunkCount, String content,
                             String createdAt) {
    }

    public DocumentVO toVO(KbDocument doc) {
        Long chunkCount = chunkMapper.selectCount(Wrappers.<KbChunk>lambdaQuery()
                .eq(KbChunk::getDocId, doc.getId())
                .eq(KbChunk::getIndexVersion, doc.getActiveIndexVersion() == null ? "" : doc.getActiveIndexVersion()));
        return new DocumentVO(String.valueOf(doc.getId()), doc.getTitle(), doc.getCategory(),
                doc.getSourceTitle(), doc.getSourceUrl() == null ? "" : doc.getSourceUrl(),
                doc.getSourceVersion() == null ? "" : doc.getSourceVersion(),
                doc.getPublishedOn() == null ? "" : doc.getPublishedOn().toString(),
                doc.getDocumentVersion(), doc.getContentHash(), doc.getStatus(), doc.getIndexStatus(),
                doc.getActiveIndexVersion(), chunkCount.intValue(),
                doc.getContent().length() > 300 ? doc.getContent().substring(0, 300) + "…" : doc.getContent(),
                doc.getCreatedAt() == null ? "" : doc.getCreatedAt().format(com.glucose.config.JacksonConfig.DATETIME));
    }

    public record UpsertReq(String title, String category, String content, String sourceTitle,
                            String sourceUrl, String sourceVersion, String publishedOn) {
    }

    public DocumentVO create(UpsertReq req) {
        validate(req);
        KbDocument doc = new KbDocument();
        doc.setTitle(req.title());
        doc.setCategory(req.category());
        doc.setContent(req.content());
        doc.setSourceTitle(req.sourceTitle());
        doc.setSourceUrl(req.sourceUrl());
        doc.setSourceVersion(req.sourceVersion());
        doc.setPublishedOn(req.publishedOn() == null || req.publishedOn().isBlank()
                ? null : java.time.LocalDate.parse(req.publishedOn()));
        doc.setDocumentVersion(1);
        doc.setContentHash(sha256(req.content()));
        doc.setStatus(0); // 入库默认停用,审核后启用
        doc.setIndexStatus("PENDING");
        documentMapper.insert(doc);
        auditService.record(LoginContext.currentUserId(), "kb.create", "kb_document",
                String.valueOf(doc.getId()), "新增知识文档 " + req.title(), null);
        return toVO(doc);
    }

    @Transactional
    public DocumentVO update(long id, UpsertReq req) {
        KbDocument doc = requireDoc(id);
        validate(req);
        boolean contentChanged = !doc.getContent().equals(req.content());
        doc.setTitle(req.title());
        doc.setCategory(req.category());
        doc.setContent(req.content());
        doc.setSourceTitle(req.sourceTitle());
        doc.setSourceUrl(req.sourceUrl());
        doc.setSourceVersion(req.sourceVersion());
        doc.setPublishedOn(req.publishedOn() == null || req.publishedOn().isBlank()
                ? null : java.time.LocalDate.parse(req.publishedOn()));
        if (contentChanged) {
            doc.setContentHash(sha256(req.content()));
            doc.setDocumentVersion(doc.getDocumentVersion() + 1);
            // 修改后旧内容立即退出新问答:重建索引前置为 PENDING 并保持停用
            doc.setIndexStatus("PENDING");
            doc.setStatus(0);
        }
        int updated = documentMapper.update(null, Wrappers.<KbDocument>lambdaUpdate()
                .eq(KbDocument::getId, id)
                .eq(KbDocument::getDocumentVersion, contentChanged ? doc.getDocumentVersion() - 1 : doc.getDocumentVersion())
                .set(KbDocument::getTitle, doc.getTitle()).set(KbDocument::getCategory, doc.getCategory())
                .set(KbDocument::getContent, doc.getContent()).set(KbDocument::getSourceTitle, doc.getSourceTitle())
                .set(KbDocument::getSourceUrl, doc.getSourceUrl()).set(KbDocument::getSourceVersion, doc.getSourceVersion())
                .set(KbDocument::getPublishedOn, doc.getPublishedOn()).set(KbDocument::getDocumentVersion, doc.getDocumentVersion())
                .set(KbDocument::getContentHash, doc.getContentHash())
                .set(contentChanged, KbDocument::getStatus, 0).set(contentChanged, KbDocument::getIndexStatus, "PENDING")
                .set(contentChanged, KbDocument::getActiveIndexVersion, null));
        if (updated != 1) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        auditService.record(LoginContext.currentUserId(), "kb.update", "kb_document",
                String.valueOf(id), "修改知识文档 " + req.title(), null);
        return toVO(doc);
    }

    public void delete(long id) {
        KbDocument doc = requireDoc(id);
        documentMapper.deleteById(id);
        auditService.record(LoginContext.currentUserId(), "kb.delete", "kb_document",
                String.valueOf(id), "删除知识文档 " + doc.getTitle(), null);
    }

    public void toggleStatus(long id, int status) {
        KbDocument doc = requireDoc(id);
        documentMapper.update(null, Wrappers.<KbDocument>lambdaUpdate().eq(KbDocument::getId, id).set(KbDocument::getStatus, status));
        // 启停立即生效;调用方(检索层)每次实时查询,无长缓存
        auditService.record(LoginContext.currentUserId(), "kb.status", "kb_document",
                String.valueOf(id), (status == 1 ? "启用" : "停用") + "知识文档 " + doc.getTitle(), null);
    }

    /* ================= 重建索引(后台) ================= */

    @Async
    public void reindexAsync(long docId) {
        try {
            reindex(docId);
        } catch (Exception e) {
            log.error("reindex failed doc={}", docId, e);
        }
    }

    public void reindex(long docId) {
        KbDocument doc = documentMapper.selectById(docId);
        if (doc == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (!embeddingService.available()) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "Embedding 服务未配置,无法建立向量索引");
        }
        int acquired = documentMapper.update(null, Wrappers.<KbDocument>lambdaUpdate()
                .eq(KbDocument::getId, docId).eq(KbDocument::getDocumentVersion, doc.getDocumentVersion())
                .ne(KbDocument::getIndexStatus, "BUILDING").set(KbDocument::getIndexStatus, "BUILDING"));
        if (acquired != 1) throw new BusinessException(ErrorCode.VERSION_CONFLICT, "文档已变化或正在建立索引");
        String newIndexVersion = "idx-" + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        try {
            List<String> chunks = chunkText(doc.getContent());
            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                float[] vector = embeddingService.embed(chunk);
                if (vector.length != embeddingService.dim()) {
                    throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,
                            "维度不匹配:配置 " + embeddingService.dim() + " 实际 " + vector.length);
                }
                KbChunk entity = new KbChunk();
                entity.setDocId(doc.getId());
                entity.setDocumentVersion(doc.getDocumentVersion());
                entity.setIndexVersion(newIndexVersion);
                entity.setChunkNo(i + 1);
                entity.setContent(chunk);
                entity.setEmbedding(EmbeddingService.toBytes(vector));
                entity.setEmbeddingModel(embeddingService.model());
                entity.setDim(vector.length);
                entity.setContentHash(sha256(chunk));
                chunkMapper.insert(entity);
            }
            // 新版本全部成功后原子切换
            int switched = documentMapper.update(null, Wrappers.<KbDocument>lambdaUpdate()
                    .eq(KbDocument::getId, docId).eq(KbDocument::getDocumentVersion, doc.getDocumentVersion())
                    .eq(KbDocument::getContentHash, doc.getContentHash()).eq(KbDocument::getIndexStatus, "BUILDING")
                    .set(KbDocument::getActiveIndexVersion, newIndexVersion).set(KbDocument::getIndexStatus, "READY"));
            if (switched != 1) throw new BusinessException(ErrorCode.VERSION_CONFLICT, "索引期间文档已变化，请重新建立索引");
            auditService.record(null, "kb.reindex", "kb_document", String.valueOf(docId),
                    "重建索引完成,切片 " + chunks.size() + " 个,版本 " + newIndexVersion, null);
        } catch (Exception e) {
            documentMapper.update(null, Wrappers.<KbDocument>lambdaUpdate()
                    .eq(KbDocument::getId, docId).eq(KbDocument::getDocumentVersion, doc.getDocumentVersion())
                    .eq(KbDocument::getIndexStatus, "BUILDING").set(KbDocument::getIndexStatus, "FAILED"));
            // 清理半成品切片
            chunkMapper.delete(Wrappers.<KbChunk>lambdaQuery()
                    .eq(KbChunk::getDocId, docId)
                    .eq(KbChunk::getIndexVersion, newIndexVersion));
            throw e;
        }
    }

    public List<KbChunk> chunks(long docId) {
        KbDocument doc = requireDoc(docId);
        if (doc.getActiveIndexVersion() == null) {
            return List.of();
        }
        return chunkMapper.selectList(Wrappers.<KbChunk>lambdaQuery()
                .eq(KbChunk::getDocId, docId)
                .eq(KbChunk::getIndexVersion, doc.getActiveIndexVersion())
                .orderByAsc(KbChunk::getChunkNo));
    }

    /* ================= 切片 ================= */

    static List<String> chunkText(String content) {
        List<String> paragraphs = new ArrayList<>();
        for (String p : content.split("\n+")) {
            if (!p.isBlank()) {
                paragraphs.add(p.trim());
            }
        }
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : paragraphs) {
            if (current.length() + paragraph.length() + 1 <= TARGET_CHUNK) {
                if (current.length() > 0) {
                    current.append('\n');
                }
                current.append(paragraph);
                continue;
            }
            if (current.length() > 0) {
                pieces.add(current.toString());
                current = new StringBuilder();
            }
            // 单段超长:按句号切分,保留重叠
            while (paragraph.length() > MAX_CHUNK) {
                int cut = sentenceCut(paragraph, TARGET_CHUNK);
                pieces.add(paragraph.substring(0, cut).trim());
                paragraph = paragraph.substring(Math.max(0, cut - OVERLAP)).trim();
            }
            current.append(paragraph);
        }
        if (current.length() > 0) {
            pieces.add(current.toString());
        }
        return pieces;
    }

    private static int sentenceCut(String text, int target) {
        int cut = Math.min(target, text.length());
        for (int i = cut; i > target / 2; i--) {
            char c = text.charAt(i - 1);
            if (c == '。' || c == '；' || c == ';' || c == '！' || c == '？' || c == '.') {
                return i;
            }
        }
        return cut;
    }

    private KbDocument requireDoc(long id) {
        KbDocument doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        return doc;
    }

    private static void validate(UpsertReq req) {
        if (req.title() == null || req.title().isBlank() || req.content() == null || req.content().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "标题和内容不能为空");
        }
        if (req.sourceTitle() == null || req.sourceTitle().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "知识来源必须填写");
        }
        if (req.sourceUrl() != null && !req.sourceUrl().isBlank()
                && !req.sourceUrl().startsWith("http://") && !req.sourceUrl().startsWith("https://")) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "来源链接只允许 http/https 地址");
        }
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
