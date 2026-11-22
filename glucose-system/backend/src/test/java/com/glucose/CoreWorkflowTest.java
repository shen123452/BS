package com.glucose;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glucose.alert.service.AlertService;
import com.glucose.analysis.service.AnalysisService;
import com.glucose.analysis.service.AnalysisSnapshotReader;
import com.glucose.common.api.ErrorCode;
import com.glucose.common.enums.RiskFlag;
import com.glucose.common.exception.BusinessException;
import com.glucose.config.AppProperties;
import com.glucose.glucose.controller.AppGlucoseController;
import com.glucose.glucose.entity.*;
import com.glucose.glucose.mapper.*;
import com.glucose.glucose.service.*;
import com.glucose.patient.entity.*;
import com.glucose.patient.mapper.*;
import com.glucose.patient.service.PatientAccessService;
import com.glucose.qa.entity.QaSession;
import com.glucose.qa.mapper.*;
import com.glucose.qa.service.QaService;
import com.glucose.security.LoginUser;
import com.glucose.system.controller.SystemAdminController;
import com.glucose.system.mapper.SysUserMapper;
import com.glucose.system.service.AuditService;
import jakarta.validation.Validation;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CoreWorkflowTest {
    @BeforeAll static void metadata() {
        for (Class<?> entity : List.of(GlucoseRecord.class, PatientProfile.class, GlucoseTargetRule.class)) {
            var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test");
            assistant.setCurrentNamespace("test." + entity.getSimpleName());
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    private static LoginUser patient(long id) {
        return new LoginUser(Long.valueOf(Long.toString(id)), "patient", "PATIENT", "REAL", 0L, "test");
    }
    private static GlucoseRecord record(long patientId, LocalDateTime time) {
        var r = new GlucoseRecord();
        r.setId(1L); r.setPatientId(Long.valueOf(Long.toString(patientId))); r.setDeleted(0);
        r.setMeasuredAt(time); r.setGlucoseMmol(new BigDecimal("6.0"));
        r.setDataSource("SMBG"); r.setDataKind("REAL"); r.setIngestMethod("MANUAL"); r.setMeasureType(1);
        return r;
    }
    private static void forbidden(Runnable action) {
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(BusinessException.class, action::run).getErrorCode());
    }

    @Test void yamlImportSettingsBindToThePropertiesActuallyUsedByTheService() {
        var source = new MapConfigurationPropertySource(Map.of("app.import.temp-dir", "test-import",
                "app.import.preview-expire-minutes", "47"));
        var properties = new Binder(source).bind("app", AppProperties.class).get();
        assertEquals("test-import", properties.getImportCfg().getTempDir());
        assertEquals(47, properties.getImportCfg().getPreviewExpireMinutes());
    }

    @Test void createAllowsServerTimeButEditStillRequiresTheExistingTimestamp() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(new AppGlucoseController.AddReq(null, new BigDecimal("6.0"), 1, null)).isEmpty());
            assertFalse(validator.validate(new AppGlucoseController.UpdateReq(null, new BigDecimal("6.0"), 1, null)).isEmpty());
            assertFalse(validator.validate(new AppGlucoseController.AddReq(null, new BigDecimal("6.0"), 1, "x".repeat(201))).isEmpty());
        }
    }

    @Test void missingManualTimestampUsesServerTime() {
        var records = mock(GlucoseRecordMapper.class);
        var alerts = mock(AlertService.class);
        when(alerts.classify(any(), any())).thenReturn(new AlertService.Classification(RiskFlag.NONE, null, null));
        var service = new GlucoseService(records, mock(PatientProfileMapper.class), alerts, mock(AuditService.class));
        var before = LocalDateTime.now().minusSeconds(1);
        var result = service.addManual(patient(1000), new GlucoseService.ManualReq(null, new BigDecimal("6.0"), 1, null));
        var capture = ArgumentCaptor.forClass(GlucoseRecord.class);
        verify(records).insert(capture.capture());
        assertFalse(capture.getValue().getMeasuredAt().isBefore(before));
        assertFalse(capture.getValue().getMeasuredAt().isAfter(LocalDateTime.now()));
        assertNotNull(result.measuredAt());
    }

    @ParameterizedTest @ValueSource(strings = {"2026-02-30 10:00:00", "2025-02-29 00:00:00", "2026-13-01 00:00:00"})
    void invalidCalendarDatesAreRejected(String time) {
        assertEquals(ErrorCode.PARAM_ERROR, assertThrows(BusinessException.class, () -> GlucoseService.parseTime(time)).getErrorCode());
    }

    @Test void leapDayIsAcceptedWithoutMovingTheDate() {
        assertEquals(LocalDateTime.of(2024, 2, 29, 10, 15), GlucoseService.parseTime("2024-02-29 10:15:00"));
    }

    @ParameterizedTest @ValueSource(longs = {1, 128, 1000})
    void ownManualRecordCanBeDeletedAndAnotherPatientCannotDeleteIt(long id) {
        var records = mock(GlucoseRecordMapper.class);
        when(records.selectById(any())).thenReturn(record(id, LocalDateTime.now()));
        var service = new GlucoseService(records, mock(PatientProfileMapper.class), mock(AlertService.class), mock(AuditService.class));
        service.deleteManual(patient(id), 1L);
        verify(records).deleteById(1L);
        forbidden(() -> service.deleteManual(patient(id + 1), 1L));
        verify(records, times(1)).deleteById(1L);
    }

    @Test void ownImportBatchAcceptsLargeIdsButRejectsOtherPatients() {
        var batches = mock(GlucoseImportBatchMapper.class);
        var batch = new GlucoseImportBatch(); batch.setId(1L); batch.setPatientId(Long.valueOf("1000"));
        batch.setStatus("READY"); batch.setTotalRows(0); batch.setValidRows(0); batch.setDuplicateRows(0);
        batch.setConflictRows(0); batch.setErrorRows(0); batch.setInsertedRows(0);
        batch.setExpiresAt(LocalDateTime.now().plusMinutes(30)); batch.setCreatedAt(LocalDateTime.now());
        when(batches.selectById(any())).thenReturn(batch);
        var service = new CgmImportService(batches, null, null, null, null, null, null);
        assertEquals("1", service.getBatch(patient(1000), 1).id());
        forbidden(() -> service.getBatch(patient(1001), 1));
    }

    @Test void ownQaSessionAcceptsLargeIdsButRejectsOtherPatients() {
        var sessions = mock(QaSessionMapper.class);
        var messages = mock(QaMessageMapper.class);
        var session = new QaSession(); session.setId(1L); session.setPatientId(Long.valueOf("1000"));
        when(sessions.selectById(any())).thenReturn(session);
        when(messages.selectList(any())).thenReturn(List.of());
        var service = new QaService(sessions, messages, null, null, null, null, null, null, null, null);
        assertTrue(service.messages(patient(1000), 1).isEmpty());
        forbidden(() -> service.messages(patient(1001), 1));
        verify(messages, times(1)).selectList(any());
    }

    @Test void boundDoctorWithLargeIdIsAllowedButUnboundDoctorIsRejected() {
        var profiles = mock(PatientProfileMapper.class);
        var profile = new PatientProfile(); profile.setDoctorId(Long.valueOf("1000"));
        when(profiles.selectOne(any())).thenReturn(profile);
        var access = new PatientAccessService(profiles);
        var bound = new LoginUser(Long.valueOf("1000"), "doctor", "DOCTOR", "REAL", 0L, "test");
        assertSame(profile, access.requireReadable(bound, 1));
        var other = new LoginUser(1001L, "other", "DOCTOR", "REAL", 0L, "test");
        forbidden(() -> access.requireReadable(other, 1));
        profile.setDoctorId(null);
        forbidden(() -> access.requireReadable(bound, 1));
    }

    @SuppressWarnings("unchecked")
    @Test void unbindingExplicitlySetsDoctorIdToSqlNull() {
        var profiles = mock(PatientProfileMapper.class);
        var access = mock(PatientAccessService.class);
        var profile = new PatientProfile(); profile.setId(99L); profile.setDoctorId(1000L);
        when(access.requireProfile(1)).thenReturn(profile);
        var controller = new SystemAdminController(null, null, mock(AuditService.class), access, profiles);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(patient(1), "test"));
        try { controller.bindDoctor(1, Map.of("doctorId", 0L)); }
        finally { SecurityContextHolder.clearContext(); }
        var capture = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(profiles).update(isNull(), capture.capture());
        var update = capture.getValue();
        assertTrue(update.getSqlSet().contains("doctor_id="));
        assertTrue(update.getSqlSegment().contains("id ="));
        assertTrue(update.getParamNameValuePairs().containsValue(null));
        assertTrue(update.getParamNameValuePairs().containsValue(99L));
        verify(profiles, never()).updateById(any(PatientProfile.class));
    }

    @SuppressWarnings("unchecked")
    @Test void summaryTrendAndDailyUseTheSameHalfOpenDateWindow() {
        var records = mock(GlucoseRecordMapper.class);
        var profiles = mock(PatientProfileMapper.class);
        var targets = mock(GlucoseTargetRuleMapper.class);
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        var profile = new PatientProfile(); profile.setDataRevision(1L); profile.setTargetRevision(1L);
        profile.setCgmTargetLow(new BigDecimal("3.9")); profile.setCgmTargetHigh(new BigDecimal("10.0"));
        when(profiles.selectOne(any())).thenReturn(profile);
        when(targets.selectList(any())).thenReturn(List.of());
        var end = LocalDate.now(); var start = end.minusDays(14);
        var allRecords = List.of(record(1, start.minusDays(1).atStartOfDay()), record(1, start.atStartOfDay()),
                record(1, end.minusDays(1).atTime(23, 55)), record(1, end.atStartOfDay()));
        when(records.selectList(any())).thenAnswer(inv -> {
            var query = (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<GlucoseRecord>) inv.getArgument(0);
            var sql = query.getSqlSegment();
            assertTrue(sql.contains("measured_at >="));
            assertTrue(sql.contains("measured_at <"));
            assertTrue(query.getParamNameValuePairs().containsValue(start.atStartOfDay()));
            assertTrue(query.getParamNameValuePairs().containsValue(end.atStartOfDay()));
            // 模拟数据库执行已断言的日期条件，含 start、不含 end。
            return allRecords.stream().filter(r -> !r.getMeasuredAt().isBefore(start.atStartOfDay())
                    && r.getMeasuredAt().isBefore(end.atStartOfDay())).toList();
        });
        var service = new AnalysisService(records, profiles, targets, mock(SysUserMapper.class), redis, new AppProperties(), new ObjectMapper(), new AnalysisSnapshotReader(records, profiles, targets));
        var query = new AnalysisService.AnalysisQuery(start, end, "CGM", "REAL");
        var summary = service.summary(1, query);
        assertEquals(2, summary.pointCount());
        assertEquals(4032, summary.expectedPointCount());
        assertEquals(2.0 / 4032, summary.dataAdequacy());
        assertNull(summary.tir());
        assertEquals(2, service.trend(1, query).totalPoints());
        assertEquals(2, service.daily(1, query).stream().mapToInt(day -> day.count()).sum());
        verify(records, times(3)).selectList(any());
    }

    @Test void invalidAnalysisWindowIsRejectedBeforeQueryingRecords() {
        var service = new AnalysisService(mock(GlucoseRecordMapper.class), null, null, null, null, null, null, null);
        var date = LocalDate.now().minusDays(1);
        assertEquals(ErrorCode.PARAM_ERROR, assertThrows(BusinessException.class, () -> service.trend(1,
                new AnalysisService.AnalysisQuery(date, date, "CGM", "REAL"))).getErrorCode());
    }

    @SuppressWarnings("unchecked")
    @Test void managementRecordsHonorDateRangeAndValidatePaging() {
        var records = mock(GlucoseRecordMapper.class);
        when(records.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(inv -> {
            Wrapper<GlucoseRecord> query = inv.getArgument(1);
            assertTrue(query.getSqlSegment().contains("measured_at >="));
            assertTrue(query.getSqlSegment().contains("measured_at <"));
            return inv.getArgument(0);
        });
        var service = new GlucoseService(records, null, null, null);
        service.pageRecords(1, "CGM", null, null, LocalDate.now().minusDays(14), LocalDate.now(), 1, 20);
        assertThrows(BusinessException.class, () -> service.pageRecords(1, null, null, null, 1, -1));
        verify(records, times(1)).selectPage(any(Page.class), any(Wrapper.class));
    }
}
