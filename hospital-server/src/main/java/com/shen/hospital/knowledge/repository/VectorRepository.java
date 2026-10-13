package com.shen.hospital.knowledge.repository;

import com.shen.hospital.common.BusinessException;

import java.sql.*;
import java.util.*;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class VectorRepository {
    private final JdbcTemplate jdbc;

    public VectorRepository(@Qualifier("vectorJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Match(String chunkId, double score) {
    }
    public record VectorInput(long chunkId, float[] embedding) {}

    @Transactional(transactionManager = "vectorTransactionManager")
    public void upsert(long chunkId, String spaceKey, float[] embedding) {
        upsertBatch(spaceKey, List.of(new VectorInput(chunkId, embedding)));
    }

    @Transactional(transactionManager = "vectorTransactionManager")
    public void upsertBatch(String spaceKey, List<VectorInput> batch) {
        if (batch == null || batch.isEmpty() || batch.size() > 32) throw invalid("向量批次大小须为1–32");
        for (VectorInput input : batch) validate(input.chunkId(), spaceKey, input.embedding());
        jdbc.batchUpdate("""
                INSERT INTO public.knowledge_embedding(chunk_id,space_key,dimension,embedding,status,error_code)
                VALUES (?,?,?,CAST(? AS public.vector),'READY',NULL)
                ON CONFLICT (chunk_id) DO UPDATE SET space_key=EXCLUDED.space_key,dimension=EXCLUDED.dimension,
                    embedding=EXCLUDED.embedding,status='READY',error_code=NULL
                """, batch, batch.size(), (statement, input) -> {
                    statement.setLong(1, input.chunkId()); statement.setString(2, spaceKey);
                    statement.setInt(3, input.embedding().length); statement.setString(4, literal(input.embedding()));
                });
    }

    public boolean verifyReady(List<Long> chunkIds, String spaceKey, int dimension) {
        if (chunkIds == null || chunkIds.isEmpty() || chunkIds.size() > 5000) return false;
        return jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            var ids = connection.createArrayOf("bigint", chunkIds.toArray(Long[]::new));
            try (var statement = connection.prepareStatement("SELECT chunk_id FROM public.knowledge_embedding WHERE chunk_id=ANY(?) AND space_key=? AND dimension=? AND status='READY' AND embedding IS NOT NULL AND vector_norm>0")) {
                statement.setArray(1, ids); statement.setString(2, spaceKey); statement.setInt(3, dimension); statement.setQueryTimeout(5);
                var found = new HashSet<Long>();
                try (var rows = statement.executeQuery()) { while (rows.next()) found.add(rows.getLong(1)); }
                return found.size() == chunkIds.size() && found.containsAll(chunkIds);
            } finally { ids.free(); }
        });
    }

    public List<Match> search(List<Long> candidateIds, String spaceKey, float[] query, int topK) {
        validate(1, spaceKey, query);
        if (topK < 1 || topK > 10) throw invalid("topK 须为1–10");
        if (candidateIds == null || candidateIds.size() > 5000 || candidateIds.stream().anyMatch(id -> id == null || id <= 0))
            throw invalid("候选片段 ID 不合法或超过首版5000片段范围");
        if (candidateIds.isEmpty()) return List.of();
        return jdbc.execute((ConnectionCallback<List<Match>>) connection -> {
            var ids = connection.createArrayOf("bigint", candidateIds.toArray(Long[]::new));
            try (var statement = connection.prepareStatement("""
                    SELECT chunk_id,1-(embedding <=> CAST(? AS public.vector)) AS score
                    FROM public.knowledge_embedding
                    WHERE chunk_id=ANY(?) AND space_key=? AND dimension=? AND status='READY'
                    ORDER BY embedding <=> CAST(? AS public.vector),chunk_id LIMIT ?
                    """)) {
                String value = literal(query);
                statement.setString(1, value);
                statement.setArray(2, ids);
                statement.setString(3, spaceKey);
                statement.setInt(4, query.length);
                statement.setString(5, value);
                statement.setInt(6, topK);
                statement.setQueryTimeout(5);
                try (var rows = statement.executeQuery()) {
                    List<Match> matches = new ArrayList<>();
                    while (rows.next())
                        matches.add(new Match(Long.toString(rows.getLong("chunk_id")), rows.getDouble("score")));
                    return matches;
                }
            } finally {
                ids.free();
            }
        });
    }

    private static String literal(float[] embedding) {
        List<String> values = new ArrayList<>(embedding.length);
        for (float value : embedding) values.add(Float.toString(value));
        return "[" + String.join(",", values) + "]";
    }

    private static void validate(long chunkId, String spaceKey, float[] embedding) {
        if (chunkId <= 0 || spaceKey == null || spaceKey.isBlank() || spaceKey.length() > 128)
            throw invalid("片段 ID 或向量空间无效");
        if (embedding == null || embedding.length < 1 || embedding.length > 16000) throw invalid("向量维度无效");
        double norm = 0;
        for (float value : embedding) {
            if (!Float.isFinite(value)) throw invalid("向量包含非有限值");
            norm += (double) value * value;
        }
        if (norm == 0 || !Double.isFinite(norm)) throw invalid("向量须为有限非零向量");
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_VECTOR", message);
    }
}
