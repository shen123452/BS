package com.glucose;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.glucose.common.api.ErrorCode;
import com.glucose.common.exception.BusinessException;
import com.glucose.medication.entity.*;
import com.glucose.medication.mapper.*;
import com.glucose.medication.service.MedicationService;
import com.glucose.patient.mapper.PatientProfileMapper;
import jakarta.validation.Validation;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MedicationWorkflowTest {
    PatientProfileMapper profiles = mock(PatientProfileMapper.class);
    MedicationPlanMapper plans = mock(MedicationPlanMapper.class);
    MedicationLogMapper logs = mock(MedicationLogMapper.class);
    MedicationService service = new MedicationService(profiles, plans, logs);
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    @BeforeAll static void metadata() {
        for (var entity : List.of(MedicationPlan.class, MedicationLog.class)) {
            var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test");
            assistant.setCurrentNamespace("test." + entity.getSimpleName());
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }
    MedicationService.PlanReq req(String name, LocalDate date) {
        return new MedicationService.PlanReq("request-1234", name, "已有处方用量", "08:30", date, null, "餐后");
    }
    MedicationPlan plan() {
        var p = new MedicationPlan(); p.setId(301L); p.setPatientId(1000L); p.setRequestId("request-1234");
        p.setDrugName("登记药名"); p.setDosage("已有处方用量"); p.setScheduledTime(LocalTime.of(8,30));
        p.setStartDate(today); p.setInstruction("餐后"); p.setActive(1); p.setVersion(1L); return p;
    }
    MedicationLog log() {
        var l = new MedicationLog(); l.setId(401L); l.setPatientId(1000L); l.setPlanId(301L);
        l.setRecordDate(today); l.setStatus("TAKEN"); l.setVersion(2L); l.setDrugName("登记药名");
        l.setTakenAt(LocalDateTime.now().minusMinutes(2)); l.setScheduledTime(LocalTime.of(8,30)); return l;
    }
    @Test void sameCreateRequestReplaysButChangedPayloadConflicts() {
        when(plans.selectOne(any())).thenReturn(plan());
        assertEquals(301L, service.create(1000, req("登记药名", today)).id());
        var error = assertThrows(BusinessException.class, () -> service.create(1000, req("另一个药名", today)));
        assertEquals(ErrorCode.VERSION_CONFLICT, error.getErrorCode()); verify(plans, never()).insert(any(MedicationPlan.class));
    }
    @Test void cannotCreateBackdatedPlanOrInvertedRange() {
        assertEquals(ErrorCode.PARAM_ERROR, assertThrows(BusinessException.class,
                () -> service.create(1000, req("药名", today.minusDays(1)))).getErrorCode());
        var r = new MedicationService.PlanReq("request-1234", "药名", "原处方用量", "08:30", today, today.minusDays(1), "");
        assertThrows(BusinessException.class, () -> service.create(1000, r));
        verify(plans, never()).insert(any(MedicationPlan.class));
    }
    @Test void noFutureOrOver90DayRecords() {
        assertThrows(BusinessException.class, () -> service.day(1000, today.plusDays(1)));
        assertThrows(BusinessException.class, () -> service.day(1000, today.minusDays(90)));
        verifyNoInteractions(logs);
    }
    @Test void statusReplayPreservesRecordedTimestampAndVersion() {
        var log = log(); when(logs.selectById(401L)).thenReturn(log);
        var result = service.setStatus(1000, 401, new MedicationService.StatusReq("TAKEN", 1L));
        assertEquals(2L, result.version()); assertTrue(result.takenAt().contains(log.getTakenAt().toLocalTime().withNano(0).toString().substring(0,5)));
        verify(logs, never()).update(any(), any());
    }
    @Test void staleOpposingStatusCannotOverwriteLatest() {
        when(logs.selectById(401L)).thenReturn(log());
        assertEquals(ErrorCode.VERSION_CONFLICT, assertThrows(BusinessException.class,
                () -> service.setStatus(1000, 401, new MedicationService.StatusReq("SKIPPED", 1L))).getErrorCode());
        verify(logs, never()).update(any(), any());
    }
    @Test void ownerIdsAbove127ComparedByValueAndForeignResourceHidden() {
        when(logs.selectById(401L)).thenReturn(log()); when(plans.selectById(301L)).thenReturn(plan());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.setStatus(1001, 401, new MedicationService.StatusReq("PENDING", 2L))).getErrorCode());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.stop(1001, 301, 1)).getErrorCode());
        verify(logs, never()).update(any(), any());
    }
    @Test void pastAndCancelledLogsCannotBeEdited() {
        var l = log(); l.setRecordDate(today.minusDays(1)); when(logs.selectById(401L)).thenReturn(l);
        assertThrows(BusinessException.class, () -> service.setStatus(1000, 401, new MedicationService.StatusReq("SKIPPED", 2L)));
        l.setRecordDate(today); l.setStatus("CANCELLED");
        assertThrows(BusinessException.class, () -> service.setStatus(1000, 401, new MedicationService.StatusReq("TAKEN", 2L)));
    }
    @Test void malformedTimesStatusAndUnboundedTextFailValidation() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var v = factory.getValidator();
            assertFalse(v.validate(new MedicationService.PlanReq("r", "x".repeat(101), "x", "24:30", today, null, "")).isEmpty());
            assertFalse(v.validate(new MedicationService.StatusReq("UNKNOWN", 0L)).isEmpty());
            assertTrue(v.validate(req("登记药名", today)).isEmpty());
        }
    }
}
