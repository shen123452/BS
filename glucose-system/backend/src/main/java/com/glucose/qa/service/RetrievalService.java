package com.glucose.qa.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.glucose.common.api.ErrorCode;
import com.glucose.common.exception.BusinessException;
import com.glucose.config.AppProperties;
import com.glucose.knowledge.entity.KbChunk;
import com.glucose.knowledge.entity.KbDocument;
import com.glucose.knowledge.mapper.KbChunkMapper;
import com.glucose.knowledge.mapper.KbDocumentMapper;
import com.glucose.knowledge.service.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索:优先当前启用文档的向量余弦 Top-K;Embedding 不可用时降级关键词检索。
 * 查询只选择启用、未删除且版本/模型匹配的切片;停用文档立即退出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;
    private final EmbeddingService embeddingService;
    private final AppProperties props;

    public record Hit(String citationId, Long docId, Long chunkId, String title, int documentVersion,
                      String sourceTitle, String sourceVersion, String content, double score, String sourceUrl) {
    }

    public record RetrievalResult(List<Hit> hits, String mode) {
    }

    public long enabledDocumentCount() {
        Long count = documentMapper.selectCount(Wrappers.<KbDocument>lambdaQuery().eq(KbDocument::getStatus, 1));
        return count == null ? 0 : count;
    }

    public RetrievalResult retrieve(String question) {
        if (embeddingService.available()) {
            try {
                List<Hit> hits = vectorSearch(question);
                if (!hits.isEmpty()) return new RetrievalResult(hits, "VECTOR");
            } catch (Exception e) {
                log.warn("vector search failed, fallback to keyword: {}", e.getMessage());
            }
        }
        return new RetrievalResult(keywordSearch(question), "KEYWORD");
    }

    private List<Hit> vectorSearch(String question) {
        List<KbDocument> docs = documentMapper.selectList(Wrappers.<KbDocument>lambdaQuery()
                .eq(KbDocument::getStatus, 1)
                .eq(KbDocument::getIndexStatus, "READY"));
        Map<Long, KbDocument> docMap = new HashMap<>();
        for (KbDocument doc : docs) {
            docMap.put(doc.getId(), doc);
        }
        if (docMap.isEmpty()) {
            return List.of();
        }
        float[] queryVector = embeddingService.embed(question);
        List<Hit> all = new ArrayList<>();
        for (KbChunk chunk : chunkMapper.selectList(Wrappers.<KbChunk>lambdaQuery()
                .in(KbChunk::getDocId, docMap.keySet()))) {
            KbDocument doc = docMap.get(chunk.getDocId());
            // 新旧模型向量不能混算;仅当前模型+维度
            if (doc == null || doc.getActiveIndexVersion() == null
                    || !doc.getActiveIndexVersion().equals(chunk.getIndexVersion())
                    || !java.util.Objects.equals(doc.getDocumentVersion(), chunk.getDocumentVersion())
                    || !embeddingService.model().equals(chunk.getEmbeddingModel())
                    || chunk.getDim() == null || chunk.getDim() != queryVector.length
                    || chunk.getEmbedding() == null || chunk.getEmbedding().length != queryVector.length * 4) {
                continue;
            }
            float score = EmbeddingService.cosine(queryVector, EmbeddingService.fromBytes(chunk.getEmbedding()));
            if (score >= props.getQa().getSimilarityThreshold()) {
                all.add(toHit(doc, chunk, score));
            }
        }
        all.sort((a, b) -> Double.compare(b.score(), a.score()));
        int k = Math.min(props.getQa().getRetrievalTopK(), all.size());
        List<Hit> top = new ArrayList<>(all.subList(0, k));
        for (int i = 0; i < top.size(); i++) {
            top.set(i, new Hit("S" + (i + 1), top.get(i).docId(), top.get(i).chunkId(), top.get(i).title(),
                    top.get(i).documentVersion(), top.get(i).sourceTitle(), top.get(i).sourceVersion(),
                    top.get(i).content(), top.get(i).score(), top.get(i).sourceUrl()));
        }
        return top;
    }

    private List<Hit> keywordSearch(String question) {
        List<KbDocument> docs = documentMapper.selectList(Wrappers.<KbDocument>lambdaQuery()
                .eq(KbDocument::getStatus, 1));
        String normalized = question.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("请问|请介绍一下|介绍一下|解释一下|是什么意思|什么是|是什么|有哪些|为什么|如何|怎么|怎样|一下|吗|呢", " ")
                .replaceAll("[^\\u4e00-\\u9fa5a-z0-9]", " ");
        List<String> terms = java.util.Arrays.stream(normalized.split("\\s+"))
                .filter(t -> t.length() >= 2).distinct().toList();
        List<Hit> hits = new ArrayList<>();
        int citation = 1;
        for (KbDocument doc : docs) {
            long matched = 0;
            String content = doc.getContent().toLowerCase(java.util.Locale.ROOT);
            String title = doc.getTitle().toLowerCase(java.util.Locale.ROOT);
            for (String term : terms) {
                if (content.contains(term)) matched++;
                if (title.contains(term)) matched += 2;
            }
            if (matched > 0) {
                String excerpt = doc.getContent().length() > 400
                        ? doc.getContent().substring(0, 400) : doc.getContent();
                hits.add(new Hit(null, doc.getId(), null, doc.getTitle(),
                        doc.getDocumentVersion(), doc.getSourceTitle(),
                        doc.getSourceVersion() == null ? "" : doc.getSourceVersion(), excerpt, matched, doc.getSourceUrl()));
            }
        }
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<Hit> top = new ArrayList<>();
        for (Hit hit : hits.subList(0, Math.min(props.getQa().getRetrievalTopK(), hits.size()))) {
            top.add(new Hit("S" + citation++, hit.docId(), hit.chunkId(), hit.title(), hit.documentVersion(),
                    hit.sourceTitle(), hit.sourceVersion(), hit.content(), hit.score(), hit.sourceUrl()));
        }
        return top;
    }

    private static RetrievalService.Hit toHit(KbDocument doc, KbChunk chunk, double score) {
        return new Hit(null, doc.getId(), chunk.getId(), doc.getTitle(), doc.getDocumentVersion(),
                doc.getSourceTitle(), doc.getSourceVersion() == null ? "" : doc.getSourceVersion(),
                chunk.getContent(), score, doc.getSourceUrl());
    }
}
