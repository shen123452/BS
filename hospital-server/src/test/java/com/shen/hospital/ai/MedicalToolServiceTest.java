package com.shen.hospital.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shen.hospital.ai.tool.MedicalToolService;
import com.shen.hospital.common.*;
import com.shen.hospital.knowledge.service.KnowledgeService;
import com.shen.hospital.masterdata.domain.Department;
import com.shen.hospital.masterdata.dto.DepartmentView;
import com.shen.hospital.masterdata.mapper.DepartmentMapper;
import com.shen.hospital.masterdata.service.MasterdataService;
import com.shen.hospital.schedule.service.ScheduleService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MedicalToolServiceTest {
    KnowledgeService knowledge=mock(KnowledgeService.class);
    DepartmentMapper departments=mock(DepartmentMapper.class);
    MasterdataService directory=mock(MasterdataService.class);
    ScheduleService schedules=mock(ScheduleService.class);
    MedicalToolService tools=new MedicalToolService(knowledge,departments,directory,schedules,new ObjectMapper());

    @Test void rejectsUnknownToolsForeignIdentityAndNonStringFields() {
        for(String args:List.of("{\"patientId\":\"99\"}","{\"keyword\":5}","[]","not-json"))
            assertThrows(BusinessException.class,() -> tools.execute(MedicalToolService.DEPARTMENTS,args,"trace"));
        assertThrows(BusinessException.class,() -> tools.execute("executeSql","{}","trace"));
        verifyNoInteractions(knowledge,departments,directory,schedules);
    }
    @Test void searchRejectsInvalidTopKBeforeAnyPaidCall() {
        for(String top:List.of("0","6","1.5","\"5\""))
            assertThrows(BusinessException.class,() -> tools.execute(MedicalToolService.SEARCH,"{\"query\":\"咳嗽\",\"topK\":"+top+"}","trace"));
        verifyNoInteractions(knowledge);
    }
    @Test void departmentKeywordUsesOnlyEnabledDirectory() {
        when(directory.enabledDepartments()).thenReturn(List.of(new DepartmentView("1","RESP","呼吸科","咳嗽等",null,false,"ENABLED")));
        assertEquals("1",tools.execute(MedicalToolService.DEPARTMENTS,"{\"keyword\":\"咳嗽\"}","trace").path("departments").get(0).path("id").asText());
    }
    @Test void scheduleToolKeepsStartAndEndDatesRatherThanOnlyFirstDay() {
        var department=new Department(); department.setStatus("ENABLED");
        when(departments.selectById(7L)).thenReturn(department);
        when(schedules.availableRange(eq(7L),any(),any(),eq(1L),eq(50L))).thenReturn(new PageResult<>(List.of(),0,1,50));
        tools.execute(MedicalToolService.SCHEDULES,"{\"departmentId\":\"7\",\"startDate\":\"2026-10-13\",\"endDate\":\"2026-10-19\"}","trace");
        verify(schedules).availableRange(7L,LocalDate.of(2026,10,13),LocalDate.of(2026,10,19),1,50);
        assertThrows(BusinessException.class,() -> tools.execute(MedicalToolService.SCHEDULES,"{\"departmentId\":\"8\",\"startDate\":\"2026-10-13\",\"endDate\":\"2026-10-19\"}","trace"));
    }
}
