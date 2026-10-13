package com.shen.hospital.ai;

import com.fasterxml.jackson.databind.*;
import com.shen.hospital.ai.agent.*;
import com.shen.hospital.common.*;
import com.shen.hospital.consultation.dto.ConfirmedSummary;
import com.shen.hospital.masterdata.dto.DepartmentView;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentResultValidatorTest {
    ObjectMapper json=new ObjectMapper();
    AgentResultValidator validator=new AgentResultValidator(json,new BusinessTime(Clock.fixed(Instant.parse("2026-10-13T01:00:00Z"),ZoneId.of("Asia/Shanghai"))));
    AgentRun run=new AgentRun();
    List<DepartmentView> directory=List.of(new DepartmentView("7","RESP","呼吸科",null,null,false,"ENABLED"));
    @BeforeEach void init(){run.setId(1L);run.setPatientId(2L);run.setSessionId(3L);run.setSummaryVersion(1);}
    ConfirmedSummary summary(String risk){
        var risks=new HashMap<String,String>();ConfirmedSummary.RISKS.keySet().forEach(k -> risks.put(k,"NO"));risks.put("chestPain",risk);
        return new ConfirmedSummary("咳嗽","三天","轻",List.of(),List.of(),risks);
    }
    JsonNode candidate(String dep,String ref)throws Exception{
        return json.readTree("{\"departmentId\":\""+dep+"\",\"alternativeDepartmentIds\":[],\"riskLevel\":\"ROUTINE\",\"reason\":\"导诊参考\",\"evidenceChunkIds\":"+ref+",\"needMoreInfo\":false,\"questions\":[]}");
    }
    @Test void validCandidateCarriesRealEvidenceSnapshot() throws Exception{
        var hit=json.readTree("{\"chunkId\":\"5\",\"title\":\"真实来源\"}");
        var result=validator.validate(run,summary("NO"),candidate("7","[\"5\"]"),Map.of("5",hit),directory);
        assertEquals("VALID",result.getValidationStatus());assertEquals(7L,result.getDepartmentId());
        assertTrue(result.getEvidenceRefs().contains("真实来源"));
    }
    @Test void hallucinatedCitationRejected() throws Exception{
        var candidate=candidate("7","[\"999\"]");
        assertEquals("AI_OUTPUT_INVALID",assertThrows(BusinessException.class,() -> validator.validate(run,summary("NO"),candidate,Map.of(),directory)).code());
    }
    @Test void absentEvidenceAndInventedDepartmentNeverProduceValidRecommendation()throws Exception{
        var result=validator.validate(run,summary("NO"),candidate("999","[]"),Map.of(),directory);
        assertEquals("LIMITED",result.getValidationStatus());assertNull(result.getDepartmentId());
    }
    @Test void unknownDangerAnswerCannotBecomeRoutine()throws Exception{
        var result=validator.validate(run,summary("UNKNOWN"),candidate("7","[]"),Map.of(),directory);
        assertEquals("ATTENTION",result.getRiskLevel());
    }
    @Test void explicitDangerTriggersEmergencyWithoutNormalAppointment() {
        var result=validator.emergency(run);assertEquals("EMERGENCY",result.getRiskLevel());assertNull(result.getDepartmentId());
        assertTrue(result.getReason().contains("120"));assertEquals("[]",result.getDoctorOptionsSnapshot());
    }
    @Test void missingRiskAnswersAreRejectedRatherThanAssumedNegative(){
        var value=new ConfirmedSummary("咳嗽","三天","轻",List.of(),List.of(),Map.of());
        assertThrows(BusinessException.class,value::validateRisks);
    }
}
