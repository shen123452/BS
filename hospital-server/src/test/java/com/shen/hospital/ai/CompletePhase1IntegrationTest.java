package com.shen.hospital.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shen.hospital.ai.prompt.PromptService;
import com.shen.hospital.ai.tool.*;
import com.shen.hospital.auth.dto.RegisterRequest;
import com.shen.hospital.auth.service.AuthService;
import com.shen.hospital.common.BusinessException;
import com.shen.hospital.masterdata.dto.DepartmentRequest;
import com.shen.hospital.masterdata.service.MasterdataService;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 完整版阶段一：数据库工具注册中心、Prompt 修订与症状标签。不调用真实模型。 */
@SpringBootTest(properties={"hospital.jwt.secret=test-only-signing-key-at-least-32-bytes-long",
        "hospital.bootstrap.enabled=false","hospital.ai.api-key=test-only-not-used"})
@EnabledIfEnvironmentVariable(named="MYSQL_PASSWORD",matches=".+")
class CompletePhase1IntegrationTest {
    @Autowired ToolRegistry registry;
    @Autowired PromptService prompts;
    @Autowired SymptomTagTools symptomTools;
    @Autowired McpToolMapper tools;
    @Autowired ToolCallLogMapper callLogs;
    @Autowired AdminToolController adminTools;
    @Autowired AuthService auth;
    @Autowired MasterdataService directory;
    @Autowired ObjectMapper json;
    @Autowired @Qualifier("mysqlDataSource") DataSource datasource;
    JdbcTemplate jdbc;
    Long user,patient;
    String departmentCode;

    @BeforeEach void init() {
        jdbc=new JdbcTemplate(datasource);
        String prefix="phase1_"+UUID.randomUUID().toString().substring(0,8);
        user=Long.valueOf(auth.register(new RegisterRequest(prefix,"TestPassword123!","阶段一工程测试（虚构）")).id());
        patient=jdbc.queryForObject("SELECT id FROM patient_profile WHERE user_id=?",Long.class,user);
        departmentCode=prefix;
        directory.createDepartment(new DepartmentRequest(prefix,"阶段一工程测试科室",null,null,false,"ENABLED"));
    }
    @AfterEach void clean() {
        Long departmentId=jdbc.queryForObject("SELECT id FROM department WHERE code=?",Long.class,departmentCode);
        jdbc.update("DELETE FROM symptom_department_rule WHERE department_id=?",departmentId);
        jdbc.update("DELETE FROM symptom_tag WHERE code LIKE 'phase1\\_%'");
        jdbc.update("DELETE FROM mcp_tool_call_log WHERE trace_id='phase1-test'");
        jdbc.update("DELETE FROM mcp_tool WHERE code LIKE 'phase1\\_%'");
        jdbc.update("DELETE FROM department WHERE id=?",departmentId);
        jdbc.update("DELETE FROM patient_profile WHERE id=?",patient);
        jdbc.update("DELETE FROM sys_user WHERE id=?",user);
    }

    private Long departmentId() {
        return jdbc.queryForObject("SELECT id FROM department WHERE code=?",Long.class,departmentCode);
    }

    @Test void seededToolPoolComesFromDatabaseWithSixBaselineTools() {
        var codes=registry.enabledCodes();
        for (String expected : List.of(ToolRegistry.KNOWLEDGE_TOOL,ToolRegistry.PROFILE_TOOL,ToolRegistry.VISITS_TOOL,
                ToolRegistry.SYMPTOM_TOOL,ToolRegistry.DEPARTMENTS_TOOL,ToolRegistry.SCHEDULES_TOOL))
            assertTrue(codes.contains(expected),"缺少基线工具 "+expected);
        var specs=registry.specifications();
        assertEquals(6,specs.size());
        assertTrue(specs.stream().anyMatch(s -> ToolRegistry.SYMPTOM_TOOL.equals(s.name())
                && s.parameters()!=null && s.parameters().required().contains("query")));
        assertTrue(specs.stream().anyMatch(s -> ToolRegistry.SCHEDULES_TOOL.equals(s.name())
                && s.parameters().required().containsAll(List.of("departmentId","startDate","endDate"))));
    }

    @Test void disabledToolLeavesPoolAndExecutionUntilReEnabled() {
        Long id=jdbc.queryForObject("SELECT id FROM mcp_tool WHERE code=?",Long.class,ToolRegistry.DEPARTMENTS_TOOL);
        int revisionBefore=jdbc.queryForObject("SELECT revision FROM mcp_tool WHERE id=?",Integer.class,id);
        Assertions.assertEquals(1,tools.flipStatus(id,"DISABLED"));
        assertFalse(registry.enabledCodes().contains(ToolRegistry.DEPARTMENTS_TOOL));
        assertFalse(registry.enabled(ToolRegistry.DEPARTMENTS_TOOL));
        var denied=assertThrows(BusinessException.class,
                () -> registry.execute(ToolRegistry.DEPARTMENTS_TOOL,"{}",null,user,"phase1-test"));
        assertEquals("TOOL_DISABLED",denied.code());
        int revisionAfter=jdbc.queryForObject("SELECT revision FROM mcp_tool WHERE id=?",Integer.class,id);
        assertEquals(revisionBefore+1,revisionAfter);
        tools.flipStatus(id,"ENABLED");
        assertTrue(registry.enabledCodes().contains(ToolRegistry.DEPARTMENTS_TOOL));
    }

    @Test void executionWritesCallLogWithSnapshotAndAdminTestSource() {
        var output=registry.execute(ToolRegistry.DEPARTMENTS_TOOL,"{\"keyword\":\"\"}",null,user,"phase1-test");
        assertTrue(output.path("departments").isArray());
        var rows=callLogs.filtered(ToolRegistry.DEPARTMENTS_TOOL,null,null);
        var latest=rows.stream().filter(r -> "phase1-test".equals(r.getTraceId())).findFirst().orElseThrow();
        assertEquals("SUCCEEDED",latest.getStatus());
        assertEquals("ADMIN_TEST",latest.getCallSource());
        assertEquals(ToolRegistry.DEPARTMENTS_TOOL,latest.getToolCodeSnapshot());
        assertEquals(user,latest.getActorUserId());
        assertNotNull(latest.getDurationMs());
    }

    @Test void patientContextToolsUseContextIdentityAndRejectIdentityArgs() {
        var profile=registry.execute(ToolRegistry.PROFILE_TOOL,"{}",patient,user,"phase1-test");
        assertEquals(patient.toString(),profile.path("patientId").asText());
        assertTrue(profile.has("chronicStatus"));
        var rejected=assertThrows(BusinessException.class,
                () -> registry.execute(ToolRegistry.PROFILE_TOOL,"{\"patientId\":999}",patient,user,"phase1-test"));
        assertEquals(400,rejected.status().value());
        var visits=registry.execute(ToolRegistry.VISITS_TOOL,"{}",patient,user,"phase1-test");
        assertTrue(visits.path("completedVisits").isArray());
        assertTrue(visits.path("completedVisits").isEmpty());
    }

    @Test void unknownExecutorAndDuplicateCodeAreRejectedOnRegistration() {
        var invalidExecutor=new AdminToolController.ToolRequest("phase1_tool_x","阶段一测试工具","仅工程测试说明",
                "{\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[]}","not_an_executor",3000,4000);
        var unknown=assertThrows(BusinessException.class,() -> adminTools.create(invalidExecutor));
        assertEquals(400,unknown.status().value());
        var created=adminTools.create(new AdminToolController.ToolRequest("phase1_tool_x","阶段一测试工具","仅工程测试说明",
                "{\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}",ToolRegistry.SYMPTOM_TOOL,3000,4000));
        assertEquals("phase1_tool_x",created.data().code());
        assertEquals("ENABLED",created.data().status());
        var duplicate=new AdminToolController.ToolRequest("phase1_tool_x","阶段一测试工具","仅工程测试说明",
                "{\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[]}",ToolRegistry.SYMPTOM_TOOL,3000,4000);
        var conflict=assertThrows(BusinessException.class,() -> adminTools.create(duplicate));
        assertEquals(409,conflict.status().value());
        assertTrue(registry.enabledCodes().contains("phase1_tool_x"));
    }

    @Test void symptomTagSearchMatchesSeedsAndReportsRedFlags() {
        var cough=symptomTools.search("咳嗽三天");
        assertTrue(cough.path("tags").size()>=1);
        assertEquals("cough",cough.path("tags").get(0).path("code").asText());
        assertFalse(cough.path("tags").get(0).path("isRedFlag").asBoolean());
        var chest=symptomTools.search("胸痛");
        assertEquals("chest_pain",chest.path("tags").get(0).path("code").asText());
        assertTrue(chest.path("tags").get(0).path("isRedFlag").asBoolean());
        assertThrows(BusinessException.class,() -> symptomTools.search(""));
    }

    @Test void promptRevisionsPublishRollbackAndContractValidation() {
        String original=prompts.current(PromptService.TRIAGE_AGENT);
        assertTrue(original.contains("riskLevel"));
        Long originalRow=prompts.currentRevisionRowId(PromptService.TRIAGE_AGENT);
        var invalid=assertThrows(BusinessException.class,
                () -> prompts.saveDraft(PromptService.TRIAGE_AGENT,"没有结果契约的提示",user));
        assertEquals(400,invalid.status().value());
        String revised=original+"\n补充阶段一工程测试说明。";
        prompts.saveDraft(PromptService.TRIAGE_AGENT,revised,user);
        var published=prompts.publish(PromptService.TRIAGE_AGENT,"阶段一工程测试发布",user);
        assertTrue(published.revision()>=2);
        assertEquals(revised,prompts.current(PromptService.TRIAGE_AGENT));
        assertNotEquals(originalRow,prompts.currentRevisionRowId(PromptService.TRIAGE_AGENT));
        prompts.rollback(PromptService.TRIAGE_AGENT,1);
        assertEquals(original,prompts.current(PromptService.TRIAGE_AGENT));
        // 恢复现场：删除测试产生的修订，指针切回初始修订。
        jdbc.update("DELETE FROM prompt_revision WHERE template_id=(SELECT id FROM prompt_template WHERE code='TRIAGE_AGENT_SYSTEM') AND revision>1");
        jdbc.update("UPDATE prompt_template SET current_revision_id=?,draft_content=? WHERE code='TRIAGE_AGENT_SYSTEM'",originalRow,original);
        assertEquals(original,prompts.current(PromptService.TRIAGE_AGENT));
    }

    @Test void preconsultPromptIsSeededAndKeepsEmergencyGuidance() {
        String prompt=prompts.current(PromptService.PRECONSULT);
        assertTrue(prompt.contains("120"));
        assertTrue(prompt.contains("急诊"));
    }
}
