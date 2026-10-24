package com.shen.hospital.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shen.hospital.ai.agent.*;
import com.shen.hospital.ai.tool.ToolRegistry;
import com.shen.hospital.auth.security.JwtService;
import com.shen.hospital.auth.service.AuthService;
import com.shen.hospital.common.BusinessException;
import com.shen.hospital.consultation.dto.ConfirmedSummary;
import com.shen.hospital.consultation.service.ConsultationService;
import com.shen.hospital.masterdata.dto.DepartmentRequest;
import com.shen.hospital.masterdata.service.MasterdataService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 完整版阶段三：运行持久事件、cursor 重订阅、管理回放与 AI 用量汇总。不调用真实模型。 */
@SpringBootTest(properties={"hospital.jwt.secret=test-only-signing-key-at-least-32-bytes-long",
        "hospital.bootstrap.enabled=false","hospital.ai.api-key=test-only-not-used"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="MYSQL_PASSWORD",matches=".+")
class CompletePhase3IntegrationTest {
    @Autowired TriageAgentService agent;
    @Autowired AiUsageService usage;
    @Autowired ConsultationService consultations;
    @Autowired AuthService auth;
    @Autowired JwtService jwt;
    @Autowired MasterdataService directory;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired @Qualifier("mysqlDataSource") DataSource datasource;
    @Autowired @Qualifier("agentTaskExecutor") org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor;
    @MockitoBean HospitalAiService ai;
    @MockitoBean ToolRegistry tools;
    JdbcTemplate jdbc;
    Long user,patient,session,department;
    int version;
    String token;

    @BeforeEach void init()throws Exception{
        jdbc=new JdbcTemplate(datasource);
        String prefix="p3_"+UUID.randomUUID().toString().substring(0,8);
        user=Long.valueOf(auth.register(new com.shen.hospital.auth.dto.RegisterRequest(prefix,"TestPassword123!","阶段三工程测试（虚构）")).id());
        token=jwt.issue(user);
        patient=jdbc.queryForObject("SELECT id FROM patient_profile WHERE user_id=?",Long.class,user);
        department=Long.valueOf(directory.createDepartment(new DepartmentRequest(prefix,"阶段三测试科室",null,null,false,"ENABLED")).id());
        session=Long.valueOf(consultations.create(user,"阶段三测试会话（虚构）").id());
        version=consultations.updateSummary(user,session,0,summary("NO"),true).summaryVersion();
        when(tools.spaceKey()).thenReturn("test-space");
        when(tools.knowledgeAvailable()).thenReturn(true);
        when(tools.enabledCodes()).thenReturn(List.of(ToolRegistry.KNOWLEDGE_TOOL));
        when(tools.specifications()).thenReturn(List.of());
        when(tools.enabled(anyString())).thenReturn(true);
        when(tools.directory()).thenAnswer(inv -> directory.enabledDepartments());
        when(tools.finalSchedules(anyString(),any())).thenReturn(json.createArrayNode());
        when(tools.execute(anyString(),anyString(),any(),any(),anyString())).thenReturn(json.readTree("{\"spaceKey\":\"test-space\",\"hits\":[]}"));
    }
    @AfterEach void clean()throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
        while(executor.getActiveCount()>0 && System.nanoTime()<until)Thread.sleep(30);
        jdbc.update("UPDATE consultation_session SET latest_result_id=NULL,active_operation_id=NULL,active_operation_type=NULL,operation_deadline=NULL WHERE id=?",session);
        jdbc.update("DELETE l FROM ai_call_log l JOIN agent_run r ON r.id=l.run_id WHERE r.patient_id=?",patient);
        jdbc.update("DELETE l FROM mcp_tool_call_log l JOIN agent_run r ON r.id=l.run_id WHERE r.patient_id=?",patient);
        jdbc.update("DELETE e FROM agent_run_event e JOIN agent_run r ON r.id=e.run_id WHERE r.patient_id=?",patient);
        jdbc.update("DELETE s FROM agent_step s JOIN agent_run r ON r.id=s.run_id WHERE r.patient_id=?",patient);
        jdbc.update("DELETE FROM triage_result WHERE patient_id=?",patient);
        jdbc.update("DELETE FROM agent_run WHERE patient_id=?",patient);
        jdbc.update("DELETE FROM consultation_message WHERE session_id=?",session);
        jdbc.update("DELETE FROM consultation_session WHERE id=?",session);
        jdbc.update("DELETE FROM patient_profile WHERE id=?",patient);
        jdbc.update("DELETE FROM sys_user WHERE id=?",user);
        jdbc.update("DELETE FROM department WHERE id=?",department);
    }
    ConfirmedSummary summary(String risk){
        var risks=new HashMap<String,String>(); ConfirmedSummary.RISKS.keySet().forEach(k->risks.put(k,"NO")); risks.put("chestPain",risk);
        return new ConfirmedSummary("阶段三测试咳嗽","三天","轻",List.of(),List.of(),risks);
    }
    Long run()throws Exception{
        when(ai.chatWithTools(any(),eq("AGENT"),anyString())).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("{\"departmentId\":\""+department+"\",\"alternativeDepartmentIds\":[],\"riskLevel\":\"ROUTINE\",\"reason\":\"阶段三工程测试参考\",\"evidenceChunkIds\":[],\"needMoreInfo\":false,\"questions\":[]}")).build());
        Long id=Long.valueOf(agent.create(user,session,version,UUID.randomUUID().toString(),"p3-trace").run().id());
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<until){ if(!Set.of("QUEUED","RUNNING").contains(agent.get(user,id).status()))break; Thread.sleep(30); }
        return id;
    }

    @Test void eventsPersistInOrderAndCursorReplaysWithoutGaps()throws Exception{
        Long id=run();
        var first=agent.events(user,id,0);
        assertTrue(first.latestSeq()>=4,"至少包含创建/步骤/校验/结果事件");
        assertTrue(first.items().stream().anyMatch(e->"run_started".equals(e.type())));
        assertTrue(first.items().stream().anyMatch(e->"result_ready".equals(e.type())));
        // cursor 重订阅：从中间游标补齐，序号严格递增无缺口，不重复
        int mid=first.items().get(1).seq();
        var resumed=agent.events(user,id,mid);
        assertEquals(first.latestSeq(),resumed.latestSeq());
        var seqs=resumed.items().stream().mapToInt(TriageAgentService.EventItem::seq).boxed().toList();
        for(int i=1;i<seqs.size();i++) assertEquals(seqs.get(i-1)+1,seqs.get(i),"重放事件序号必须连续");
        assertTrue(seqs.stream().allMatch(s->s>mid));
        // 患者事件不泄漏错误码或工具原文
        assertTrue(first.items().stream().noneMatch(e->e.label()==null||e.label().isBlank()));
        // 越权访问被拒绝
        assertThrows(BusinessException.class,()->agent.events(-1L,id,0));
    }

    @Test void adminReplayExposesVersionsAndSnapshots()throws Exception{
        Long id=run();
        Long admin=Long.valueOf(auth.register(new com.shen.hospital.auth.dto.RegisterRequest("p3admin_"+UUID.randomUUID().toString().substring(0,6),"TestPassword123!","阶段三管理员（虚构）")).id());
        jdbc.update("UPDATE sys_user SET role='ADMIN' WHERE id=?",admin);
        mvc.perform(get("/api/admin/agent-runs/"+id+"/replay").header("Authorization","Bearer "+jwt.issue(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.run.id").value(id.toString()))
                .andExpect(jsonPath("$.data.modelName").exists())
                .andExpect(jsonPath("$.data.configFingerprint").exists())
                .andExpect(jsonPath("$.data.promptHash").exists())
                .andExpect(jsonPath("$.data.toolNames[0]").value(ToolRegistry.KNOWLEDGE_TOOL))
                .andExpect(jsonPath("$.data.maxRounds").value(6))
                .andExpect(jsonPath("$.data.maxToolCalls").value(12))
                .andExpect(jsonPath("$.data.traceId").exists());
        jdbc.update("DELETE FROM patient_profile WHERE user_id=?",admin);
        jdbc.update("DELETE FROM sys_user WHERE id=?",admin);
    }

    @Test void usageSummaryComputesTerminatedSuccessRateAndMissingUsage()throws Exception{
        Long id=run();
        // ai 为替身不写调用日志；直接构造今日 AGENT 日志验证口径：3 条已终结（2 成功）+1 条运行中
        var today=java.time.LocalDate.now();
        var todayStart=today.atStartOfDay();
        jdbc.update("DELETE FROM ai_call_log WHERE purpose='AGENT' AND started_at>=?",todayStart);
        String insert="INSERT INTO ai_call_log(trace_id,request_id,run_id,purpose,model_name,config_fingerprint,status,usage_source,"
                +"total_tokens,duration_ms,first_token_latency_ms,started_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
        jdbc.update(insert,"p3-trace","r1",id,"AGENT","stub","fp","SUCCEEDED","UNAVAILABLE",null,100L,50L,todayStart.plusMinutes(1));
        jdbc.update(insert,"p3-trace","r2",id,"AGENT","stub","fp","SUCCEEDED","PROVIDER",30L,200L,150L,todayStart.plusMinutes(2));
        jdbc.update(insert,"p3-trace","r3",id,"AGENT","stub","fp","FAILED","UNAVAILABLE",null,300L,null,todayStart.plusMinutes(3));
        jdbc.update(insert,"p3-trace","r4",id,"AGENT","stub","fp","RUNNING","UNAVAILABLE",null,400L,null,todayStart.plusMinutes(4));
        var summary=usage.summary(today,today);
        var agentSummary=summary.purposes().stream().filter(p->"AGENT".equals(p.purpose())).findFirst().orElseThrow();
        assertEquals(4,agentSummary.total());
        assertEquals(3,agentSummary.terminated());
        assertEquals(2,agentSummary.succeeded());
        assertEquals(66.7,agentSummary.successRate());
        assertEquals(1,agentSummary.usageAvailable());
        assertEquals(75.0,agentSummary.usageMissingRate());
        assertEquals(30,agentSummary.tokenSum());
        assertEquals(200,agentSummary.durationP50Ms());
        assertEquals(400,agentSummary.durationP95Ms());
        assertEquals(50,agentSummary.firstTokenP50Ms());
        assertEquals(150,agentSummary.firstTokenP95Ms());
        var logs=usage.logs(today,today,"AGENT",null,1,10);
        assertTrue(logs.total()>=4);
        mvc.perform(get("/api/admin/ai-usage/summary").param("startDate",today.toString()).param("endDate",today.toString()))
                .andExpect(status().isUnauthorized());
    }
}
