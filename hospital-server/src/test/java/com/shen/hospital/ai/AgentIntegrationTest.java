package com.shen.hospital.ai;

import com.fasterxml.jackson.databind.*;
import com.shen.hospital.ai.agent.*;
import com.shen.hospital.ai.tool.ToolRegistry;
import com.shen.hospital.auth.domain.Role;
import com.shen.hospital.auth.security.JwtService;
import com.shen.hospital.auth.service.AuthService;
import com.shen.hospital.common.*;
import com.shen.hospital.consultation.dto.ConfirmedSummary;
import com.shen.hospital.consultation.service.ConsultationService;
import com.shen.hospital.masterdata.dto.DepartmentRequest;
import com.shen.hospital.masterdata.service.MasterdataService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"hospital.jwt.secret=test-only-signing-key-at-least-32-bytes-long",
        "hospital.bootstrap.enabled=false","hospital.ai.api-key=test-only-not-used"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="MYSQL_PASSWORD",matches=".+")
class AgentIntegrationTest {
    @Autowired TriageAgentService agent;
    @Autowired ConsultationService consultations;
    @Autowired AuthService auth;
    @Autowired JwtService jwt;
    @Autowired MasterdataService directory;
    @Autowired BusinessTime time;
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
    CountDownLatch release;

    @BeforeEach void init(){
        jdbc=new JdbcTemplate(datasource);
        String prefix="agent_"+UUID.randomUUID().toString().substring(0,8);
        user=Long.valueOf(auth.register(new com.shen.hospital.auth.dto.RegisterRequest(prefix,"TestPassword123!","导诊工程测试（虚构）")).id());
        token=jwt.issue(user);
        patient=jdbc.queryForObject("SELECT id FROM patient_profile WHERE user_id=?",Long.class,user);
        department=Long.valueOf(directory.createDepartment(new DepartmentRequest(prefix,"导诊工程测试科室",null,null,false,"ENABLED")).id());
        session=Long.valueOf(consultations.create(user,"导诊工程测试（虚构）").id());
        version=consultations.updateSummary(user,session,0,summary("NO"),true).summaryVersion();
        when(tools.spaceKey()).thenReturn("test-space");
        when(tools.knowledgeAvailable()).thenReturn(true);
        when(tools.enabledCodes()).thenReturn(java.util.List.of(ToolRegistry.KNOWLEDGE_TOOL));
        when(tools.specifications()).thenReturn(List.of());
        when(tools.directory()).thenAnswer(inv -> directory.enabledDepartments());
        when(tools.finalSchedules(anyString(),any())).thenReturn(json.createArrayNode());
    }
    @AfterEach void clean()throws Exception{
        if(release!=null)release.countDown();
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
        while(executor.getActiveCount()>0 && System.nanoTime()<until)Thread.sleep(30);
        assertEquals(0,executor.getActiveCount(),"agent workers must end before test fixture cleanup");
        jdbc.update("UPDATE consultation_session SET latest_result_id=NULL,active_operation_id=NULL,active_operation_type=NULL,operation_deadline=NULL WHERE id=?",session);
        jdbc.update("DELETE l FROM ai_call_log l JOIN agent_run r ON r.id=l.run_id WHERE r.patient_id=?",patient);
        jdbc.update("DELETE l FROM mcp_tool_call_log l JOIN agent_run r ON r.id=l.run_id WHERE r.patient_id=?",patient);
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
        return new ConfirmedSummary("工程测试咳嗽","三天","轻",List.of(),List.of(),risks);
    }
    ChatResponse answer(String text){return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();}
    ChatResponse tool(String id){return ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder()
            .id(id).name(ToolRegistry.KNOWLEDGE_TOOL).arguments("{\"query\":\"咳嗽\"}").build())).build();}
    String candidate(String ref){return "{\"departmentId\":\""+department+"\",\"alternativeDepartmentIds\":[],\"riskLevel\":\"ROUTINE\",\"reason\":\"仅工程测试的导诊参考\",\"evidenceChunkIds\":"+ref+",\"needMoreInfo\":false,\"questions\":[]}";}
    TriageAgentService.RunView await(Long id)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        TriageAgentService.RunView value;
        do{value=agent.get(user,id);if(!Set.of("QUEUED","RUNNING").contains(value.status()))return value;Thread.sleep(30);}while(System.nanoTime()<until);
        fail("run did not terminate");return value;
    }
    Long create(){return Long.valueOf(agent.create(user,session,version,UUID.randomUUID().toString(),"test-trace").run().id());}

    @Test void actualToolSelectionAndCitationPersistAndReplayWithoutSecondModelCall()throws Exception{
        when(ai.chatWithTools(any(),eq("AGENT"),anyString())).thenReturn(tool("call-search"),answer(candidate("[\"5\"]")));
        when(tools.enabled(ToolRegistry.KNOWLEDGE_TOOL)).thenReturn(true);
        when(tools.execute(eq(ToolRegistry.KNOWLEDGE_TOOL),anyString(),any(),any(),anyString())).thenReturn(json.readTree("{\"spaceKey\":\"test-space\",\"hits\":[{\"chunkId\":\"5\",\"title\":\"工程测试来源\",\"excerpt\":\"虚构工程资料\"}]}"));
        String client="stable-client";
        Long id=Long.valueOf(agent.create(user,session,version,client,"trace").run().id());
        var complete=await(id);assertEquals("SUCCEEDED",complete.status());assertEquals(2,complete.actualRounds());assertEquals(1,complete.actualToolCalls());
        assertTrue(complete.steps().stream().allMatch(s->"SUCCEEDED".equals(s.status())));
        var result=agent.result(user,Long.valueOf(complete.resultId()));assertTrue(result.canBook());assertEquals("5",result.evidenceRefs().get(0).path("chunkId").asText());
        consultations.updateSummary(user,session,version,summary("NO"),true);
        assertFalse(agent.result(user,Long.valueOf(complete.resultId())).canBook());
        assertEquals(id.toString(),agent.create(user,session,version,client,"trace").run().id());
        verify(ai,times(2)).chatWithTools(any(),eq("AGENT"),anyString());
        mvc.perform(get("/api/patient/triage-runs/"+id).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.steps[0].inputRedacted").doesNotExist());
    }
    @Test void emergencyUsesCodeRuleWithoutModelOrKnowledge()throws Exception{
        version=consultations.updateSummary(user,session,version,summary("YES"),true).summaryVersion();
        when(tools.knowledgeAvailable()).thenReturn(false);
        var value=await(create());assertEquals("SUCCEEDED",value.status());
        var result=agent.result(user,Long.valueOf(value.resultId()));
        assertEquals("EMERGENCY",result.riskLevel());assertFalse(result.canBook());verifyNoInteractions(ai);
    }
    @Test void forgedCitationCreatesNoResult()throws Exception{
        when(ai.chatWithTools(any(),eq("AGENT"),anyString())).thenReturn(answer(candidate("[\"999\"]")));
        var value=await(create());assertEquals("FAILED",value.status());assertEquals("AI_OUTPUT_INVALID",value.errorCode());
        assertNull(value.resultId());assertNull(jdbc.queryForObject("SELECT active_operation_id FROM consultation_session WHERE id=?",String.class,session));
    }
    @Test void roundBudgetCannotRunIndefinitely()throws Exception{
        var count=new java.util.concurrent.atomic.AtomicInteger();
        when(ai.chatWithTools(any(),eq("AGENT"),anyString())).thenAnswer(inv->tool("tool-"+count.incrementAndGet()));
        when(tools.enabled(anyString())).thenReturn(true);
        when(tools.execute(anyString(),anyString(),any(),any(),anyString())).thenReturn(json.readTree("{\"spaceKey\":\"test-space\",\"hits\":[]}"));
        var value=await(create());assertEquals("AGENT_BUDGET_EXCEEDED",value.errorCode());assertEquals(6,value.actualRounds());assertEquals(6,value.actualToolCalls());
        verify(ai,times(6)).chatWithTools(any(),anyString(),anyString());
    }
    @Test void cancelClearsSessionAndLateModelCannotPublish()throws Exception{
        CountDownLatch entered=new CountDownLatch(1);release=new CountDownLatch(1);
        when(ai.chatWithTools(any(),eq("AGENT"),anyString())).thenAnswer(inv->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException ignored){}return answer(candidate("[]"));});
        Long id=create();assertTrue(entered.await(3,TimeUnit.SECONDS));assertTrue(agent.cancel(user,id));
        release.countDown();var value=await(id);assertEquals("CANCELLED",value.status());assertNull(value.resultId());
        assertNull(jdbc.queryForObject("SELECT active_operation_id FROM consultation_session WHERE id=?",String.class,session));
    }
    @Test void expiredRunIsEndedBeforeLateReply()throws Exception{
        CountDownLatch entered=new CountDownLatch(1);release=new CountDownLatch(1);
        when(ai.chatWithTools(any(),eq("AGENT"),anyString())).thenAnswer(inv->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException ignored){}return answer(candidate("[]"));});
        Long id=create();assertTrue(entered.await(3,TimeUnit.SECONDS));
        jdbc.update("UPDATE agent_run SET deadline_at=? WHERE id=?",time.now().minusSeconds(1),id);agent.expire();release.countDown();
        var value=await(id);assertEquals("FAILED",value.status());assertEquals("AGENT_TIMEOUT",value.errorCode());assertNull(value.resultId());
    }
    @Test void unavailableKnowledgeCreatesNoRun() {
        when(tools.knowledgeAvailable()).thenReturn(false);
        assertEquals("KNOWLEDGE_UNAVAILABLE",assertThrows(BusinessException.class,this::create).code());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_run WHERE patient_id=?",Integer.class,patient));verifyNoInteractions(ai);
    }
    @Test void summarySchemaAndOtherPatientOwnershipAreEnforced()throws Exception{
        mvc.perform(put("/api/patient/consultations/"+session+"/summary").header("Authorization","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedSummaryVersion\":1,\"confirm\":true,\"summary\":{\"patientId\":99}}"))
                .andExpect(status().isBadRequest());
        when(ai.chatWithTools(any(),eq("AGENT"),anyString())).thenReturn(answer(candidate("[]")));
        Long id=create();await(id);
        assertThrows(BusinessException.class,()->agent.get(-1L,id));assertThrows(BusinessException.class,()->agent.cancel(-1L,id));
        mvc.perform(get("/api/patient/triage-runs/"+id)).andExpect(status().isUnauthorized());
    }
    @Test void historyRecoversStoredRunsAndRejectsOtherPatients()throws Exception{
        when(ai.chatWithTools(any(),eq("AGENT"),anyString())).thenReturn(answer(candidate("[]")));
        Long first=create();await(first);Long second=create();await(second);
        mvc.perform(get("/api/patient/consultations/"+session+"/triage-runs").param("pageSize","1").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value(second.toString()))
                .andExpect(jsonPath("$.data.items[0].createdAt").exists());
        assertEquals(first.toString(),agent.history(user,session,2,1).items().getFirst().id());
        assertThrows(BusinessException.class,()->agent.history(-1L,session,1,20));
        verify(ai,times(2)).chatWithTools(any(),anyString(),anyString());
    }
    @Test void softDeletionRetainsMessagesRunsResultsAndRejectsFurtherSessionUse()throws Exception{
        version=consultations.updateSummary(user,session,version,summary("YES"),true).summaryVersion();
        var completed=await(create());Long resultId=Long.valueOf(completed.resultId());
        mvc.perform(delete("/api/patient/consultations/"+session).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM consultation_session WHERE id=? AND deleted_at IS NOT NULL",Integer.class,session));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_run WHERE session_id=?",Integer.class,session));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM triage_result WHERE id=?",Integer.class,resultId));
        assertFalse(agent.result(user,resultId).isCurrent());assertFalse(agent.result(user,resultId).canBook());
        mvc.perform(get("/api/patient/consultations/"+session).header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
        mvc.perform(get("/api/patient/consultations/"+session+"/triage-runs").header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
        assertThrows(BusinessException.class,()->consultations.messages(user,session));verifyNoInteractions(ai);
    }
    @Test void activeTriagePreventsDeletionUntilCancelled()throws Exception{
        CountDownLatch entered=new CountDownLatch(1);release=new CountDownLatch(1);
        when(ai.chatWithTools(any(),eq("AGENT"),anyString())).thenAnswer(inv->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException ignored){}return answer(candidate("[]"));});
        Long id=create();assertTrue(entered.await(3,TimeUnit.SECONDS));
        mvc.perform(delete("/api/patient/consultations/"+session).header("Authorization","Bearer "+token))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SESSION_BUSY"));
        assertTrue(agent.cancel(user,id));release.countDown();await(id);consultations.delete(user,session);
        assertEquals("CANCELLED",agent.get(user,id).status());
    }
}
