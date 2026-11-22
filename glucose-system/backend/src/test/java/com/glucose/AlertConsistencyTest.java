package com.glucose;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glucose.alert.controller.AlertController;
import com.glucose.alert.entity.*;
import com.glucose.alert.mapper.*;
import com.glucose.alert.service.AlertService;
import com.glucose.common.api.ErrorCode;
import com.glucose.common.exception.BusinessException;
import com.glucose.glucose.entity.GlucoseRecord;
import com.glucose.glucose.mapper.GlucoseRecordMapper;
import com.glucose.patient.mapper.PatientProfileMapper;
import com.glucose.patient.service.PatientAccessService;
import com.glucose.security.LoginUser;
import com.glucose.system.service.AuditService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AlertConsistencyTest {
    private final AlertRuleMapper rules = mock(AlertRuleMapper.class);
    private final AlertRecordMapper events = mock(AlertRecordMapper.class);
    private final GlucoseRecordMapper records = mock(GlucoseRecordMapper.class);
    private final PatientProfileMapper profiles = mock(PatientProfileMapper.class);
    private final AuditService audit = mock(AuditService.class);
    private final AlertService service = new AlertService(rules, events, records, audit, new ObjectMapper(), profiles);
    private final List<AlertRecord> saved = new ArrayList<>();
    private final LocalDateTime now = LocalDateTime.now().withNano(0);
    @BeforeAll static void metadata() {
        for (Class<?> entity : List.of(GlucoseRecord.class, AlertRecord.class, AlertRule.class)) {
            var helper = new MapperBuilderAssistant(new MybatisConfiguration(), "alerts");
            helper.setCurrentNamespace("test." + entity.getSimpleName());
            TableInfoHelper.initTableInfo(helper, entity);
        }
    }
    @BeforeEach void setup() {
        AlertRule low = new AlertRule(); low.setRuleType("LOW"); low.setEnabled(1); low.setWarningThreshold(new BigDecimal("3.9")); low.setSevereThreshold(new BigDecimal("3.0")); low.setVersion(1L);
        AlertRule high = new AlertRule(); high.setRuleType("HIGH"); high.setEnabled(1); high.setWarningThreshold(new BigDecimal("13.9")); high.setSevereThreshold(new BigDecimal("16.7")); high.setVersion(1L);
        when(rules.selectList(any())).thenReturn(List.of(low, high));
        when(events.selectList(any())).thenReturn(List.of());
        when(events.update(isNull(), any())).thenReturn(1);
        when(events.insert(any(AlertRecord.class))).thenAnswer(inv -> {
            AlertRecord event = inv.getArgument(0);
            assertTrue(event.getActiveKey() == null || saved.stream().noneMatch(e -> event.getActiveKey().equals(e.getActiveKey())), "unique active key");
            event.setId((long) saved.size() + 1); saved.add(event); return 1;
        });
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    GlucoseRecord p(int minutesAgo, String value) {
        var p = new GlucoseRecord(); p.setId((long) (100 - minutesAgo)); p.setMeasuredAt(now.minusMinutes(minutesAgo)); p.setGlucoseMmol(new BigDecimal(value)); return p;
    }
    void compute(boolean historical, GlucoseRecord... points) {
        when(records.selectList(any())).thenReturn(List.of(points));
        service.recomputeAfterChange(201, "CGM", "REAL", historical);
    }
    @Test void separatedEpisodesAreBothSavedAndOnlyFreshTerminalEpisodeIsOpen() {
        compute(false, p(50, "3.4"), p(45, "3.2"), p(2, "2.8"));
        assertEquals(2, saved.size());
        assertEquals("END_UNKNOWN", saved.get(0).getEventStatus()); assertNull(saved.get(0).getActiveKey());
        assertEquals("OPEN", saved.get(1).getEventStatus()); assertNotNull(saved.get(1).getActiveKey());
        verify(profiles).requireLock(201);
    }
    @Test void fifteenMinutesAndOneSecondAlreadySplitsAnEpisode() {
        var first = p(18, "3.4"); first.setMeasuredAt(now.minusMinutes(17).minusSeconds(1));
        compute(false, first, p(2, "3.3")); assertEquals(2, saved.size());
    }
    @Test void nearbyRecoveryEndsButRecoveryAfterGapDoesNotInventRecoveryTime() {
        compute(false, p(50, "3.4"), p(45, "6.0"), p(30, "3.3"), p(2, "6.0"));
        assertEquals("ENDED", saved.get(0).getEventStatus()); assertEquals(now.minusMinutes(45), saved.get(0).getEndAt());
        assertEquals("END_UNKNOWN", saved.get(1).getEventStatus()); assertNull(saved.get(1).getEndAt());
    }
    @Test void historicalImportNeverCreatesActiveRealtimeEvent() {
        compute(true, p(1, "2.5")); assertEquals("END_UNKNOWN", saved.get(0).getEventStatus()); assertNull(saved.get(0).getActiveKey()); assertEquals(1, saved.get(0).getIsHistorical());
    }
    @Test void directionChangeKeepsBothEventsAndDoesNotKeepPreviousActiveKey() {
        compute(false, p(5, "3.2"), p(1, "17.0"));
        assertEquals(2, saved.size()); assertNull(saved.get(0).getActiveKey()); assertEquals("OPEN", saved.get(1).getEventStatus());
    }
    @Test void upgradeReopensHandledEventAndExplicitlyClearsOldHandlerFields() {
        compute(false, p(3, "3.4"));
        AlertRecord event = saved.remove(0); event.setHandleStatus("HANDLED"); event.setHandledBy(101L); event.setHandledAt(now); event.setHandleNote("old note");
        when(events.selectList(any())).thenReturn(List.of(event));
        compute(false, p(3, "3.4"), p(1, "2.5"));
        assertEquals("PENDING", event.getHandleStatus()); assertNull(event.getHandledBy()); assertNull(event.getHandleNote()); assertEquals(2L, event.getNoticeVersion());
        var capture = org.mockito.ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(events).update(isNull(), capture.capture());
        String set = capture.getValue().getSqlSet();
        assertTrue(set.contains("handled_by=")); assertTrue(set.contains("handle_note="));
        assertFalse(set.contains("patient_seen_notice_version"));
    }
    @Test void updateCollisionIsReturnedAsConflictAndNotSilentlySwallowed() {
        compute(false, p(3, "3.4")); AlertRecord event = saved.remove(0);
        when(events.selectList(any())).thenReturn(List.of(event)); when(events.update(isNull(), any())).thenReturn(0);
        assertEquals(ErrorCode.VERSION_CONFLICT, assertThrows(BusinessException.class, () -> compute(false, p(3, "3.4"), p(1, "2.5"))).getErrorCode());
    }
    @Test void staleKeyIsReleasedBeforeReplacementInsert() {
        compute(false, p(4, "3.4")); AlertRecord old = saved.remove(0);
        when(events.selectList(any())).thenReturn(List.of(old));
        compute(false, p(2, "3.4"));
        var order = inOrder(events); order.verify(events).insert(any(AlertRecord.class)); order.verify(events).selectList(any()); order.verify(events).update(isNull(), any()); order.verify(events).insert(any(AlertRecord.class));
        assertEquals("INVALIDATED", old.getEventStatus()); assertNull(old.getActiveKey());
    }
    @Test void handlingUsesVersionConditionIncrementsVersionAndRejectsLostRace() {
        compute(false, p(2, "2.5")); AlertRecord event = saved.get(0);
        when(events.selectById(1L)).thenReturn(event);
        var user = new LoginUser(101L, "doctor", "DOCTOR", "REAL", 0L, "test");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, "test", List.of()));
        var controller = new AlertController(events, mock(PatientAccessService.class), audit, profiles);
        controller.handle(1, Map.of("version", "1", "handleNote", "handled"));
        assertEquals(2L, event.getVersion());
        assertEquals(ErrorCode.VERSION_CONFLICT, assertThrows(BusinessException.class, () -> controller.handle(1, Map.of("version", 1))).getErrorCode());
        when(events.update(isNull(), any())).thenReturn(0);
        assertEquals(ErrorCode.VERSION_CONFLICT, assertThrows(BusinessException.class, () -> controller.handle(1, Map.of("version", 2))).getErrorCode());
        verify(audit, times(1)).record(eq(101L), eq("alert.handle"), anyString(), anyString(), anyString(), any());
    }
}
