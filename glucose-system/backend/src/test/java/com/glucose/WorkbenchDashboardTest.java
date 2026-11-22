package com.glucose;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.glucose.alert.entity.AlertRecord;
import com.glucose.alert.mapper.AlertRecordMapper;
import com.glucose.common.api.ErrorCode;
import com.glucose.common.exception.BusinessException;
import com.glucose.glucose.entity.GlucoseRecord;
import com.glucose.glucose.mapper.GlucoseRecordMapper;
import com.glucose.patient.entity.PatientProfile;
import com.glucose.patient.mapper.PatientProfileMapper;
import com.glucose.security.LoginUser;
import com.glucose.system.entity.SysUser;
import com.glucose.system.mapper.SysUserMapper;
import com.glucose.system.service.AuditService;
import com.glucose.system.controller.WorkbenchDashboardController;
import com.glucose.system.service.WorkbenchDashboardService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkbenchDashboardTest {

    @BeforeAll
    static void metadata() {
        for (Class<?> entity : List.of(SysUser.class, PatientProfile.class, GlucoseRecord.class, AlertRecord.class)) {
            var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "dashboard-test");
            assistant.setCurrentNamespace("dashboard-test." + entity.getSimpleName());
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @Test
    void administratorOverviewCountsEnabledPatientsAndSystemActivity() {
        var users = mock(SysUserMapper.class);
        var profiles = mock(PatientProfileMapper.class);
        var records = mock(GlucoseRecordMapper.class);
        var alerts = mock(AlertRecordMapper.class);
        when(users.selectCount(any())).thenReturn(3L);
        when(records.selectCount(any())).thenReturn(84L);
        when(alerts.selectCount(any())).thenReturn(2L);

        var result = new WorkbenchDashboardService(users, profiles, records, alerts)
                .overview(new LoginUser(1L, "admin", "ADMIN", "REAL", 0L, "test"));

        assertEquals(3, result.activePatients());
        assertEquals(84, result.recordsLast7Days());
        assertEquals(2, result.pendingAlerts());
        assertNotNull(result.updatedAt());
        verify(profiles, never()).selectList(any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void doctorOverviewScopesEveryCountToBoundEnabledPatients() {
        var users = mock(SysUserMapper.class);
        var profiles = mock(PatientProfileMapper.class);
        var records = mock(GlucoseRecordMapper.class);
        var alerts = mock(AlertRecordMapper.class);
        var first = new PatientProfile(); first.setUserId(701L);
        var second = new PatientProfile(); second.setUserId(702L);
        when(profiles.selectList(any())).thenReturn(List.of(first, second));
        var active = new SysUser(); active.setId(701L);
        when(users.selectList(any())).thenReturn(List.of(active));
        when(records.selectCount(any())).thenReturn(9L);
        when(alerts.selectCount(any())).thenReturn(1L);

        var result = new WorkbenchDashboardService(users, profiles, records, alerts)
                .overview(new LoginUser(55L, "doctor", "DOCTOR", "REAL", 0L, "test"));

        assertEquals(1, result.activePatients());
        assertEquals(9, result.recordsLast7Days());
        assertEquals(1, result.pendingAlerts());

        var recordQuery = org.mockito.ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(records).selectCount(recordQuery.capture());
        assertTrue(recordQuery.getValue().getSqlSegment().contains("patient_id IN"));
        assertTrue(recordQuery.getValue().getSqlSegment().contains("measured_at >="));
        assertTrue(recordQuery.getValue().getSqlSegment().contains("measured_at <"));
        assertTrue(recordQuery.getValue().getParamNameValuePairs().containsValue(701L));
        assertFalse(recordQuery.getValue().getParamNameValuePairs().containsValue(702L));
        assertTrue(recordQuery.getValue().getParamNameValuePairs().containsValue(result.updatedAt().minusDays(7)));
        assertTrue(recordQuery.getValue().getParamNameValuePairs().containsValue(result.updatedAt()));

        var alertQuery = org.mockito.ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(alerts).selectCount(alertQuery.capture());
        assertTrue(alertQuery.getValue().getSqlSegment().contains("patient_id IN"));
        assertTrue(alertQuery.getValue().getSqlSegment().contains("event_status <>"));
        assertTrue(alertQuery.getValue().getParamNameValuePairs().containsValue(701L));
        assertFalse(alertQuery.getValue().getParamNameValuePairs().containsValue(702L));
    }

    @Test
    void doctorWithNoBoundActivePatientsGetsZeroCountsWithoutReadingHealthData() {
        var users = mock(SysUserMapper.class);
        var profiles = mock(PatientProfileMapper.class);
        var records = mock(GlucoseRecordMapper.class);
        var alerts = mock(AlertRecordMapper.class);
        when(profiles.selectList(any())).thenReturn(List.of());

        var result = new WorkbenchDashboardService(users, profiles, records, alerts)
                .overview(new LoginUser(55L, "doctor", "DOCTOR", "REAL", 0L, "test"));

        assertEquals(0, result.activePatients());
        assertEquals(0, result.recordsLast7Days());
        assertEquals(0, result.pendingAlerts());
        verifyNoInteractions(records, alerts);
    }

    @Test
    void patientRoleCannotReadTheManagementDashboard() {
        var service = new WorkbenchDashboardService(mock(SysUserMapper.class), mock(PatientProfileMapper.class),
                mock(GlucoseRecordMapper.class), mock(AlertRecordMapper.class));
        var error = assertThrows(BusinessException.class,
                () -> service.overview(new LoginUser(701L, "patient", "PATIENT", "REAL", 0L, "test")));
        assertEquals(ErrorCode.FORBIDDEN, error.getErrorCode());
    }

    @Test
    void administratorDashboardReadIsAudited() {
        var service = mock(WorkbenchDashboardService.class);
        var audit = mock(AuditService.class);
        var controller = new WorkbenchDashboardController(service, audit);
        var admin = new LoginUser(5L, "admin", "ADMIN", "REAL", 0L, "test");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin, "test"));
        try {
            controller.overview();
        } finally {
            SecurityContextHolder.clearContext();
        }
        verify(audit).record(5L, "admin.view.dashboard", "aggregate", "", "管理员查看工作台聚合概览", null);
        verify(service).overview(admin);
    }

    @Test
    void doctorDashboardReadDoesNotCreateAdministratorAudit() {
        var service = mock(WorkbenchDashboardService.class);
        var audit = mock(AuditService.class);
        var controller = new WorkbenchDashboardController(service, audit);
        var doctor = new LoginUser(55L, "doctor", "DOCTOR", "REAL", 0L, "test");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(doctor, "test"));
        try {
            controller.overview();
        } finally {
            SecurityContextHolder.clearContext();
        }
        verify(audit, never()).record(anyLong(), anyString(), anyString(), anyString(), anyString(), any());
        verify(service).overview(doctor);
    }
}
