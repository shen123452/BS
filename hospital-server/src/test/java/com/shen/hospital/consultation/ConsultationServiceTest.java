package com.shen.hospital.consultation;

import com.shen.hospital.ai.AiProperties;
import com.shen.hospital.ai.ModelFactory;
import com.shen.hospital.common.BusinessException;
import com.shen.hospital.common.BusinessTime;
import com.shen.hospital.consultation.domain.ConsultationMessage;
import com.shen.hospital.consultation.domain.ConsultationSession;
import com.shen.hospital.consultation.dto.SendMessageRequest;
import com.shen.hospital.consultation.mapper.ConsultationMessageMapper;
import com.shen.hospital.consultation.mapper.ConsultationSessionMapper;
import com.shen.hospital.consultation.service.ConsultationService;
import com.shen.hospital.masterdata.mapper.PatientProfileMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConsultationServiceTest {
    private ConsultationSessionMapper sessions;
    private ConsultationMessageMapper messages;
    private PatientProfileMapper patients;
    private ConsultationService service;
    private ConsultationSession session;

    @BeforeEach
    void setUp() {
        sessions = mock(ConsultationSessionMapper.class);
        messages = mock(ConsultationMessageMapper.class);
        patients = mock(PatientProfileMapper.class);
        var models = new ModelFactory(new AiProperties("http://127.0.0.1/v1", "test-key", "qwen-plus", "text-embedding-v4", 1024, 30));
        var clock = Clock.fixed(LocalDateTime.of(2026, 10, 13, 9, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant(), ZoneId.of("Asia/Shanghai"));
        service = new ConsultationService(sessions, messages, patients, models, new BusinessTime(clock));
        session = new ConsultationSession();
        session.setId(20L);
        session.setPatientId(10L);
        session.setTitle("新问诊");
        session.setSummaryVersion(0);
        when(patients.findIdByUserId(1L)).thenReturn(10L);
        when(sessions.lockById(20L)).thenReturn(session);
        when(sessions.startOperation(eq(20L), anyString(), eq("CHAT"), any(LocalDateTime.class))).thenReturn(1);
        AtomicLong id = new AtomicLong(100);
        doAnswer(invocation -> {
            ConsultationMessage row = invocation.getArgument(0);
            row.setId(id.incrementAndGet());
            return 1;
        }).when(messages).insert(any(ConsultationMessage.class));
        when(messages.recentCompletedBefore(eq(20L), anyLong(), anyInt())).thenReturn(List.of());
    }

    @Test
    void newMessagePersistsPairAndBuildsSafeStreamingRequest() {
        var prepared = service.begin(1L, 20L, new SendMessageRequest("client-1", "  最近咳嗽两天  "));
        assertFalse(prepared.replay());
        assertEquals("STREAMING", prepared.state());
        assertEquals(2, prepared.request().messages().size());
        assertTrue(prepared.request().messages().getFirst().toString().contains("不作疾病诊断"));
        verify(messages, times(2)).insert(any(ConsultationMessage.class));
        verify(sessions).startOperation(eq(20L), eq(prepared.operationId()), eq("CHAT"), any(LocalDateTime.class));
        assertEquals("最近咳嗽两天", session.getTitle());
    }

    @Test
    void matchingClientIdReplaysWithoutCallingModelAgain() {
        var user = message(101L, "USER", "client-1", "request-1", "咳嗽");
        var assistant = message(102L, "ASSISTANT", "client-1", "request-1", "请补充持续时间");
        assistant.setStatus("COMPLETED");
        when(messages.findUserByClient(20L, "client-1")).thenReturn(user);
        when(messages.findAssistantByRequest(20L, "request-1")).thenReturn(assistant);
        var prepared = service.begin(1L, 20L, new SendMessageRequest("client-1", "咳嗽"));
        assertTrue(prepared.replay());
        assertEquals("请补充持续时间", prepared.replayText());
        verify(messages, never()).insert(any(ConsultationMessage.class));
    }

    @Test
    void rejectsReusedIdWithDifferentContentAndConcurrentSessionOperation() {
        var user = message(101L, "USER", "client-1", "request-1", "头痛");
        when(messages.findUserByClient(20L, "client-1")).thenReturn(user);
        var conflict = assertThrows(BusinessException.class,
                () -> service.begin(1L, 20L, new SendMessageRequest("client-1", "腹痛")));
        assertEquals("MESSAGE_ID_CONFLICT", conflict.code());

        when(messages.findUserByClient(20L, "client-2")).thenReturn(null);
        session.setActiveOperationId("another-operation");
        session.setOperationDeadline(LocalDateTime.of(2026, 10, 13, 9, 0, 20));
        var busy = assertThrows(BusinessException.class,
                () -> service.begin(1L, 20L, new SendMessageRequest("client-2", "发烧")));
        assertEquals("SESSION_BUSY", busy.code());
    }

    @Test
    void sessionOwnershipIsAlwaysCheckedBeforeSaving() {
        session.setPatientId(99L);
        assertEquals("RESOURCE_NOT_FOUND", assertThrows(BusinessException.class,
                () -> service.begin(1L, 20L, new SendMessageRequest("client-1", "症状"))).code());
        verify(messages, never()).insert(any(ConsultationMessage.class));
    }

    private static ConsultationMessage message(long id, String role, String clientId, String requestId, String content) {
        var row = new ConsultationMessage();
        row.setId(id);
        row.setSessionId(20L);
        row.setRole(role);
        row.setClientMessageId(clientId);
        row.setRequestId(requestId);
        row.setContent(content);
        row.setStatus("COMPLETED");
        return row;
    }
}
