package com.shen.hospital.ai.agent;

import com.fasterxml.jackson.databind.*;
import com.shen.hospital.common.*;
import com.shen.hospital.consultation.dto.ConfirmedSummary;
import com.shen.hospital.masterdata.dto.DepartmentView;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class AgentResultValidator {
    private final ObjectMapper json;
    private final BusinessTime time;
    public AgentResultValidator(ObjectMapper json, BusinessTime time) { this.json=json; this.time=time; }

    public TriageResult validate(AgentRun run, ConfirmedSummary summary, JsonNode candidate,
                                 Map<String,JsonNode> retrieved, List<DepartmentView> departments) {
        if (candidate==null || !candidate.isObject() || !candidate.path("reason").isTextual()
                || candidate.path("reason").asText().isBlank() || candidate.path("reason").asText().length()>2000
                || !candidate.path("needMoreInfo").isBoolean() || !candidate.path("questions").isArray()
                || !candidate.path("alternativeDepartmentIds").isArray() || !candidate.path("evidenceChunkIds").isArray()) throw invalid();
        String risk=candidate.path("riskLevel").asText("");
        if (!Set.of("ROUTINE","ATTENTION","EMERGENCY").contains(risk)) throw invalid();
        if (summary.emergency()) return emergency(run);
        if (summary.uncertainRisk() && "ROUTINE".equals(risk)) risk="ATTENTION";
        List<JsonNode> refs=new ArrayList<>();
        if(candidate.path("evidenceChunkIds").size()>5) throw invalid();
        for(var id:candidate.path("evidenceChunkIds")) {
            String key=id(id);
            if(!retrieved.containsKey(key)) throw invalid();
            if(refs.stream().noneMatch(r -> key.equals(r.path("chunkId").asText()))) refs.add(retrieved.get(key));
        }
        var enabled=new HashMap<String,DepartmentView>();
        departments.stream().filter(d -> "ENABLED".equals(d.status()) && !d.isEmergency()).forEach(d -> enabled.put(d.id(),d));
        String primary=candidate.path("departmentId").isNull()||candidate.path("departmentId").isMissingNode()?null:id(candidate.path("departmentId"));
        boolean real=primary!=null && enabled.containsKey(primary);
        var alternatives=new ArrayList<String>();
        if(candidate.path("alternativeDepartmentIds").size()>3) throw invalid();
        for(var dep:candidate.path("alternativeDepartmentIds")) {
            String key=id(dep);
            if(enabled.containsKey(key) && !Objects.equals(key,primary) && !alternatives.contains(key)) alternatives.add(key);
        }
        var questions=new ArrayList<String>();
        if(candidate.path("questions").size()>5) throw invalid();
        for(var question:candidate.path("questions")) {
            if(!question.isTextual() || question.asText().isBlank() || question.asText().length()>200) throw invalid();
            questions.add(question.asText());
        }
        boolean need=candidate.path("needMoreInfo").asBoolean();
        if(need && questions.isEmpty()) questions.add("请补充主要不适、持续时间和严重程度。");
        var result=base(run);
        result.setRiskLevel(risk);
        result.setValidationStatus(need?"NEED_MORE_INFO":real && !refs.isEmpty()?"VALID":"LIMITED");
        result.setDepartmentId(real && !need && !"EMERGENCY".equals(risk)?Long.valueOf(primary):null);
        result.setAlternativeDepartmentIds(json.valueToTree(need || "EMERGENCY".equals(risk)?List.of():alternatives).toString());
        result.setReason("EMERGENCY".equals(risk)?"请立即拨打120或就近急诊，不要等待普通门诊预约。":candidate.path("reason").asText());
        result.setEvidenceRefs(json.valueToTree(refs).toString()); result.setMissingInformation(json.valueToTree(questions).toString());
        return result;
    }
    public TriageResult emergency(AgentRun run) {
        var result=base(run); result.setRiskLevel("EMERGENCY"); result.setValidationStatus("VALID");
        result.setReason("你确认存在危险信号，请立即拨打120或就近急诊，不要等待普通门诊预约。");
        return result;
    }
    private TriageResult base(AgentRun run) {
        var result=new TriageResult(); result.setRunId(run.getId()); result.setPatientId(run.getPatientId());
        result.setSessionId(run.getSessionId()); result.setSummaryVersion(run.getSummaryVersion());
        result.setAlternativeDepartmentIds("[]"); result.setEvidenceRefs("[]"); result.setMissingInformation("[]");
        result.setDoctorOptionsSnapshot("[]"); result.setCreatedAt(time.now()); result.setExpiresAt(time.now().plusHours(24));
        return result;
    }
    private static String id(JsonNode node) {
        if(!node.isTextual() && !node.isIntegralNumber()) throw invalid();
        try { long value=Long.parseLong(node.asText()); if(value>0)return Long.toString(value); } catch(Exception ignored) {}
        throw invalid();
    }
    private static BusinessException invalid() { return new BusinessException(org.springframework.http.HttpStatus.BAD_GATEWAY,
            "AI_OUTPUT_INVALID", "模型结果无法通过科室、格式或引用校验，请重试或直接预约"); }
}
