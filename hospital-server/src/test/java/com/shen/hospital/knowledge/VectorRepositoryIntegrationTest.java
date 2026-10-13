package com.shen.hospital.knowledge;

import com.shen.hospital.knowledge.repository.VectorRepository;
import com.shen.hospital.common.BusinessException;

import java.util.List;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"hospital.jwt.secret=test-only-signing-key-at-least-32-bytes-long", "hospital.bootstrap.enabled=false"})
@EnabledIfEnvironmentVariable(named = "VECTOR_DB_PASSWORD", matches = ".+")
@Transactional(transactionManager = "vectorTransactionManager")
class VectorRepositoryIntegrationTest {
    @Autowired
    VectorRepository vectors;

    @Test
    void upsertFilteringSpaceAndRanking() {
        vectors.upsert(900000001, "test/space-a", new float[]{1, 0, 0});
        vectors.upsert(900000002, "test/space-a", new float[]{0, 1, 0});
        vectors.upsert(900000003, "test/space-b", new float[]{1, 0, 0});
        var hits = vectors.search(List.of(900000001L, 900000002L, 900000003L), "test/space-a", new float[]{1, 0, 0}, 5);
        assertEquals(2, hits.size());
        assertEquals("900000001", hits.getFirst().chunkId());
        assertEquals(1, hits.getFirst().score(), 1e-6);
        assertEquals(1, vectors.search(List.of(900000002L), "test/space-a", new float[]{1, 0, 0}, 5).size());
        assertTrue(vectors.search(List.of(), "test/space-a", new float[]{1, 0, 0}, 5).isEmpty());
        vectors.upsert(900000001, "test/space-b", new float[]{1, 0});
        assertTrue(vectors.search(List.of(900000001L), "test/space-a", new float[]{1, 0, 0}, 5).isEmpty());
        assertEquals(1, vectors.search(List.of(900000001L), "test/space-b", new float[]{1, 0}, 5).size());
    }

    @Test
    void invalidVectorsRejectedBeforeSql() {
        assertThrows(BusinessException.class, () -> vectors.upsert(1, "space", new float[]{0, 0}));
        assertThrows(BusinessException.class, () -> vectors.upsert(1, "space", new float[]{Float.NaN, 1}));
        assertThrows(BusinessException.class, () -> vectors.search(List.of(1L), "space", new float[]{1}, 11));
    }
}
