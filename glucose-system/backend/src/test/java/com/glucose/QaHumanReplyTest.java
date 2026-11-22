package com.glucose;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glucose.alert.mapper.AlertRecordMapper;
import com.glucose.analysis.service.AnalysisService;
import com.glucose.config.AppProperties;
import com.glucose.qa.entity.*;
import com.glucose.qa.mapper.*;
import com.glucose.qa.service.*;
import com.glucose.security.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QaHumanReplyTest {
    QaSessionMapper sessions = mock(QaSessionMapper.class); QaMessageMapper messages = mock(QaMessageMapper.class);
    QaService service = new QaService(sessions, messages, mock(RetrievalService.class), mock(LlmService.class),
            mock(AnalysisService.class), mock(AlertRecordMapper.class), mock(AuthStateService.class), new AppProperties(), new ObjectMapper(), mock(QaRequestStore.class));
    @BeforeAll static void metadata() { var a = new MapperBuilderAssistant(new MybatisConfiguration(), "qa-human"); a.setCurrentNamespace("test.QaSession"); TableInfoHelper.initTableInfo(a, QaSession.class); }
    QaSession session() { var s = new QaSession(); s.setId(11L); s.setPatientId(201L); return s; }
    @Test void createsHumanMessageWithoutSourcesAndMarksMode() {
        when(sessions.lockById(11)).thenReturn(session()); when(messages.selectOne(any())).thenReturn(null);
        doAnswer(i -> { var m = i.getArgument(0, QaMessage.class); m.setId(31L); return 1; }).when(messages).insert(any(QaMessage.class));
        var result = service.reply(new LoginUser(99L,"doctor","DOCTOR","REAL",0,"jti"),11,"reply-123456","请按医生原有计划记录并复诊");
        assertEquals("HUMAN",result.getAnswerMode()); assertEquals("COMPLETED",result.getStatus()); assertEquals(0,result.getDegraded());
        verify(messages).insert(argThat((QaMessage m) -> "assistant".equals(m.getRole()) && m.getSources() == null)); verify(sessions).update(any(),any());
    }
    @Test void sameRequestSameContentReplaysDifferentContentConflicts() {
        var existing = new QaMessage(); existing.setAnswerMode("HUMAN"); existing.setContent("原回复");
        when(sessions.lockById(11)).thenReturn(session()); when(messages.selectOne(any())).thenReturn(existing);
        assertSame(existing, service.reply(new LoginUser(99L,"doctor","DOCTOR","REAL",0,"jti"),11,"reply-123456","原回复"));
        assertEquals(40901, assertThrows(com.glucose.common.exception.BusinessException.class, () -> service.reply(new LoginUser(99L,"doctor","DOCTOR","REAL",0,"jti"),11,"reply-123456","新回复")).getErrorCode().getCode());
        verify(messages, never()).insert(any(QaMessage.class));
    }
    @Test void invalidInputAndMissingSessionAreRejected() {
        assertThrows(com.glucose.common.exception.BusinessException.class, () -> service.reply(new LoginUser(99L,"doctor","DOCTOR","REAL",0,"jti"),11,"x","内容"));
        when(sessions.lockById(11)).thenReturn(null);
        assertEquals(40401, assertThrows(com.glucose.common.exception.BusinessException.class, () -> service.reply(new LoginUser(99L,"doctor","DOCTOR","REAL",0,"jti"),11,"reply-123456","内容")).getErrorCode().getCode());
    }
}
