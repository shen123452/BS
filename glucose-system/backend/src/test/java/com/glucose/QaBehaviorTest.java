package com.glucose;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glucose.alert.mapper.AlertRecordMapper;
import com.glucose.analysis.algorithm.GlucoseStatistics;
import com.glucose.analysis.service.AnalysisService;
import com.glucose.config.AppProperties;
import com.glucose.knowledge.entity.KbDocument;
import com.glucose.knowledge.mapper.KbDocumentMapper;
import com.glucose.knowledge.mapper.KbChunkMapper;
import com.glucose.knowledge.service.EmbeddingService;
import com.glucose.qa.entity.*;
import com.glucose.qa.mapper.*;
import com.glucose.qa.service.*;
import com.glucose.security.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QaBehaviorTest {
    @BeforeAll static void metadata() {
        for (Class<?> entity : List.of(KbDocument.class, QaSession.class)) {
            var helper = new MapperBuilderAssistant(new MybatisConfiguration(), "qa");
            helper.setCurrentNamespace("test." + entity.getSimpleName()); TableInfoHelper.initTableInfo(helper, entity);
        }
    }

    private final RetrievalService retrieval = mock(RetrievalService.class);
    private final AnalysisService analysis = mock(AnalysisService.class);
    private final LlmService llm = mock(LlmService.class);
    private final AlertRecordMapper alerts = mock(AlertRecordMapper.class);
    private final QaRequestStore requests = mock(QaRequestStore.class);
    private QaService service() {
        var auth = mock(AuthStateService.class);
        when(auth.tryAcquireQaQuota(anyLong(), anyInt())).thenReturn(true);
        when(auth.acquireQaLease(anyLong())).thenReturn("lease");
        var session = new QaSession(); session.setId(1L); session.setPatientId(201L);
        var message = new QaMessage(); message.setId(2L); message.setSessionId(1L); message.setRole("assistant");
        when(requests.reserve(any(), any())).thenReturn(new QaRequestStore.Reservation(session, message, true));
        return new QaService(mock(QaSessionMapper.class), mock(QaMessageMapper.class), retrieval, llm,
                analysis, alerts, auth, new AppProperties(), new ObjectMapper(), requests);
    }
    private QaService.AskVO ask(QaService service, String question) {
        return service.ask(new LoginUser(201L,"test","PATIENT","REAL",0L,"jti"), new QaService.AskReq(1L,"request",question));
    }
    private void emptyContext() {
        when(analysis.summary(anyLong(), any())).thenAnswer(inv -> {
            AnalysisService.AnalysisQuery q = inv.getArgument(1);
            return GlucoseStatistics.summarize(q.dataSource(), "REAL", List.of(), LocalDate.now().minusDays(7),
                    LocalDate.now(), new BigDecimal("3.9"), new BigDecimal("10.0"), List.of(), 1, 1);
        });
        when(retrieval.retrieve(anyString())).thenReturn(new RetrievalService.RetrievalResult(List.of(), "KEYWORD"));
    }

    @ParameterizedTest @ValueSource(strings = {"你好", "您好！", "Hi!", "hello"})
    void greetingIsHelpfulWithoutCallingModelsOrReadingHealthData(String question) {
        var result = ask(service(), question);
        assertEquals("FIXED_NOTICE", result.answerMode()); assertTrue(result.answer().contains("你好"));
        assertTrue(result.sources().isEmpty()); verifyNoInteractions(retrieval, analysis, alerts);
        verify(llm, never()).chat(anyString(), anyString());
    }
    @Test void emptyKnowledgeIsDistinguishedFromAnUncoveredQuestion() {
        emptyContext(); var service = service();
        var result = ask(service, "血糖是什么");
        assertEquals("NO_COVERAGE", result.answerMode()); assertTrue(result.answer().contains("尚无已启用"));
        verify(llm, never()).chat(anyString(), anyString());
    }
    @Test void greetingPrefixCannotBypassKnowledgeChecksForHealthQuestions() {
        emptyContext(); when(retrieval.enabledDocumentCount()).thenReturn(1L);
        var result = ask(service(), "你好，请告诉我血糖是什么");
        assertEquals("NO_COVERAGE", result.answerMode()); assertTrue(result.answer().contains("暂未找到"));
        verify(retrieval).retrieve("你好，请告诉我血糖是什么");
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"[]","null","[null]","{}","[{}]","bad"})
    void emptyOrMalformedSnapshotsDoNotAdvertiseSources(String json) {
        var message = new QaMessage(); message.setRole("assistant"); message.setStatus("COMPLETED"); message.setSources(json);
        assertFalse(service().hasSources(message));
    }
    @Test void onlyCompletedAssistantMessagesWithValidSnapshotsAdvertiseSources() {
        var service = service(); var message = new QaMessage(); message.setRole("assistant"); message.setStatus("COMPLETED");
        message.setSources("[{\"citationId\":\"S1\",\"docId\":\"1\",\"excerpt\":\"资料原文\"}]");
        assertTrue(service.hasSources(message)); message.setStatus("PROCESSING"); assertFalse(service.hasSources(message));
        message.setStatus("COMPLETED"); message.setRole("user"); assertFalse(service.hasSources(message));
    }
    @Test void naturalChineseQuestionsAndLatinAbbreviationsMatchPublishedKnowledge() {
        var docs = mock(KbDocumentMapper.class); var embedding = mock(EmbeddingService.class);
        var d = new KbDocument(); d.setId(1L); d.setTitle("血糖与 CGM"); d.setContent("血糖是血液中的葡萄糖。CGM 表示连续葡萄糖监测。");
        d.setSourceTitle("fixture"); d.setDocumentVersion(1);
        when(docs.selectList(any())).thenReturn(List.of(d));
        var search = new RetrievalService(docs, mock(KbChunkMapper.class), embedding, new AppProperties());
        assertEquals(1, search.retrieve("血糖是什么？").hits().size());
        assertEquals(1, search.retrieve("什么是cgm？").hits().size());
        assertTrue(search.retrieve("股票价格").hits().isEmpty());
        assertEquals("S1", search.retrieve("血糖是什么").hits().get(0).citationId());
    }
    @Test void noVectorMatchFallsBackToPublishedKeywordKnowledge() {
        var docs = mock(KbDocumentMapper.class); var embedding = mock(EmbeddingService.class);
        var d = new KbDocument(); d.setId(1L); d.setTitle("血糖"); d.setContent("血糖是血液中的葡萄糖。"); d.setDocumentVersion(1);
        when(embedding.available()).thenReturn(true);
        when(docs.selectList(any())).thenReturn(List.of(), List.of(d));
        var search = new RetrievalService(docs, mock(KbChunkMapper.class), embedding, new AppProperties());
        var result = search.retrieve("血糖是什么"); assertEquals("KEYWORD", result.mode()); assertEquals(1, result.hits().size());
        verify(embedding, never()).embed(anyString());
    }

    @Test void streamRagEmitsDeltasAndPreservesValidatedCitations() {
        emptyContext();
        when(retrieval.retrieve(anyString())).thenReturn(new RetrievalService.RetrievalResult(
                List.of(new RetrievalService.Hit("S1", 10L, 100L, "血糖概念", 1, "指南", "v1", "血糖即血液中葡萄糖。", 0.9, "")), "KEYWORD"));
        when(llm.available()).thenReturn(true);
        when(llm.model()).thenReturn("qwen-plus");
        when(llm.chatStream(anyString(), anyString(), any())).thenAnswer(inv -> {
            java.util.function.Consumer<String> onDelta = inv.getArgument(2);
            onDelta.accept("血糖是血液中的");
            onDelta.accept("葡萄糖 [S1]。");
            return "血糖是血液中的葡萄糖 [S1]。";
        });
        List<String> deltas = new ArrayList<>();
        List<String> replaces = new ArrayList<>();
        var result = service().ask(new LoginUser(201L, "test", "PATIENT", "REAL", 0L, "jti"),
                new QaService.AskReq(1L, "req-stream-1", "血糖是什么"),
                new QaService.StreamSink() {
                    @Override public void onDelta(String text) { deltas.add(text); }
                    @Override public void onReplace(String fullText) { replaces.add(fullText); }
                });
        assertEquals("RAG", result.answerMode());
        assertEquals(List.of("血糖是血液中的", "葡萄糖 [S1]。"), deltas);
        assertTrue(replaces.isEmpty());
        assertEquals(1, result.sources().size());
    }

    @Test void streamInvalidCitationReplacesBubbleWithVerifiedExcerpt() {
        emptyContext();
        when(retrieval.retrieve(anyString())).thenReturn(new RetrievalService.RetrievalResult(
                List.of(new RetrievalService.Hit("S1", 10L, 100L, "血糖概念", 1, "指南", "v1", "血糖即血液中葡萄糖。", 0.9, "")), "KEYWORD"));
        when(llm.available()).thenReturn(true);
        when(llm.chatStream(anyString(), anyString(), any())).thenAnswer(inv -> {
            java.util.function.Consumer<String> onDelta = inv.getArgument(2);
            onDelta.accept("胡乱编造的引用 [S9]");
            return "胡乱编造的引用 [S9]";
        });
        List<String> replaces = new ArrayList<>();
        var result = service().ask(new LoginUser(201L, "test", "PATIENT", "REAL", 0L, "jti"),
                new QaService.AskReq(1L, "req-stream-2", "血糖是什么"),
                new QaService.StreamSink() {
                    @Override public void onDelta(String text) {}
                    @Override public void onReplace(String fullText) { replaces.add(fullText); }
                });
        assertEquals("RETRIEVAL_ONLY", result.answerMode());
        assertTrue(result.degraded());
        assertEquals(1, replaces.size());
        assertTrue(replaces.get(0).contains("引用校验未通过"));
    }

    @Test void llmStreamLineParserHandlesOpenAiSseFormat() {
        StringBuilder full = new StringBuilder();
        List<String> deltas = new ArrayList<>();
        LlmService.parseStreamLine(": keep-alive", full, deltas::add);
        LlmService.parseStreamLine("data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}", full, deltas::add);
        LlmService.parseStreamLine("data: {\"choices\":[{\"delta\":{\"content\":\"世界\"}}]}", full, deltas::add);
        LlmService.parseStreamLine("data: [DONE]", full, deltas::add);
        assertEquals("你好世界", full.toString());
        assertEquals(List.of("你好", "世界"), deltas);
    }
}
