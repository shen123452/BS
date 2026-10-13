package com.shen.hospital.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shen.hospital.ai.ModelFactory;
import com.shen.hospital.ai.tool.MedicalToolService;
import com.shen.hospital.common.*;
import com.shen.hospital.consultation.dto.ConfirmedSummary;
import com.shen.hospital.consultation.mapper.ConsultationSessionMapper;
import com.shen.hospital.masterdata.mapper.PatientProfileMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import javax.sql.DataSource;

@Service
public class AgentStore {
    private final AgentRunMapper runs;
    private final AgentStepMapper steps;
    private final TriageResultMapper results;
    private final ConsultationSessionMapper sessions;
    private final PatientProfileMapper patients;
    private final ModelFactory models;
    private final ObjectMapper json;
    private final BusinessTime time;
    private final JdbcTemplate jdbc;

    public AgentStore(AgentRunMapper runs, AgentStepMapper steps, TriageResultMapper results,
                      ConsultationSessionMapper sessions, PatientProfileMapper patients, ModelFactory models,
                      ObjectMapper json, BusinessTime time, @Qualifier("mysqlDataSource") DataSource datasource) {
        this.runs=runs; this.steps=steps; this.results=results; this.sessions=sessions; this.patients=patients;
        this.models=models; this.json=json; this.time=time; this.jdbc=new JdbcTemplate(datasource);
    }
    public record Accepted(AgentRun run, boolean created) {}

    @Transactional(transactionManager="mysqlTransactionManager", isolation=Isolation.READ_COMMITTED)
    public Accepted create(Long userId, Long sessionId, int version, String clientId, String traceId, String prompt, String space, boolean knowledgeAvailable) {
        var patient = patients.lockByUserId(userId);
        if (patient == null) throw BusinessException.missing("患者档案");
        var session = sessions.lockById(sessionId);
        if (session == null || !patient.getId().equals(session.getPatientId())) throw BusinessException.missing("问诊会话");
        String requestHash = hash(sessionId + ":" + version);
        var previous = runs.byClient(patient.getId(), clientId);
        if (previous != null) {
            if (!requestHash.equals(previous.getRequestHash())) throw BusinessException.conflict("REQUEST_ID_CONFLICT", "该请求号已用于其他导诊内容");
            return new Accepted(previous, false);
        }
        if (session.getSummaryVersion() != version || session.getSummaryConfirmedAt() == null)
            throw BusinessException.conflict("SUMMARY_NOT_CONFIRMED", "请先确认最新症状摘要");
        ConfirmedSummary summary = summary(session.getSummaryJson());
        if (session.getActiveOperationId() != null) throw BusinessException.conflict("SESSION_BUSY", "当前会话有任务正在处理，请稍候");
        if (!summary.emergency()) {
            models.requireConfigured();
            if (!knowledgeAvailable) throw new BusinessException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "KNOWLEDGE_UNAVAILABLE", "请管理员先启用已完成处理的导诊资料，或直接使用普通预约");
        }
        var run = new AgentRun();
        run.setRunNo("TR" + UUID.randomUUID().toString().replace("-", ""));
        run.setPatientId(patient.getId()); run.setSessionId(sessionId); run.setClientRequestId(clientId);
        run.setRequestHash(requestHash); run.setSummaryVersion(version);
        var snapshot = json.createObjectNode();
        snapshot.set("summary", json.valueToTree(summary));
        snapshot.put("gender", patient.getGender());
        if (patient.getAge() == null) snapshot.putNull("age"); else snapshot.put("age", patient.getAge());
        snapshot.put("chronicStatus", patient.getChronicStatus()); snapshot.put("chronicHistory", patient.getChronicHistory());
        snapshot.put("allergyStatus", patient.getAllergyStatus()); snapshot.put("allergyHistory", patient.getAllergyHistory());
        run.setInputSnapshot(snapshot.toString()); run.setModelName(models.config().chatModel());
        run.setConfigFingerprint(models.fingerprint()); run.setPromptHash(hash(prompt));
        run.setToolNames(json.valueToTree(MedicalToolService.NAMES).toString());
        run.setKnowledgeSnapshot(json.valueToTree(Map.of("spaceKey", space)).toString());
        run.setSafetyRuleVersion("explicit-risk-v1"); run.setStatus("QUEUED");
        run.setMaxRounds(4); run.setMaxToolCalls(6); run.setActualRounds(0); run.setActualToolCalls(0);
        run.setDeadlineAt(time.now().plusSeconds(60)); run.setTraceId(traceId == null ? UUID.randomUUID().toString() : traceId);
        if (sessions.startTriage(sessionId, patient.getId(), version, run.getRunNo(), run.getDeadlineAt()) != 1)
            throw BusinessException.conflict("SESSION_BUSY", "当前会话有任务正在处理，请稍候");
        runs.insert(run);
        return new Accepted(run, true);
    }

    public ConfirmedSummary summary(String text) {
        try { var value=json.readValue(text, ConfirmedSummary.class); value.validateRisks(); return value; }
        catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw BusinessException.invalid("症状摘要格式不合法"); }
    }
    public AgentRun owned(Long userId, Long id) {
        Long patientId=patients.findIdByUserId(userId);
        var run=patientId==null?null:runs.owned(id, patientId);
        if(run==null) throw BusinessException.missing("导诊任务");
        return run;
    }
    public TriageResult ownedResult(Long userId, Long id) {
        Long patientId=patients.findIdByUserId(userId);
        var result=patientId==null?null:results.owned(id, patientId);
        if(result==null) throw BusinessException.missing("导诊结果");
        return result;
    }
    public TriageResult byRun(Long id) { return results.byRun(id); }
    public PageResult<AgentRun> list(String status,long page,long size) {
        return PageResult.from(runs.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page,size),
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentRun>()
                        .eq(status!=null,AgentRun::getStatus,status).orderByDesc(AgentRun::getId)),r->r);
    }
    public AgentRun detail(Long id) { var run=runs.selectById(id); if(run==null)throw BusinessException.missing("导诊任务");return run; }
    public List<AgentStep> steps(Long id) { return steps.listByRun(id); }

    @Transactional(transactionManager="mysqlTransactionManager")
    public boolean claim(Long id) {
        var initial=runs.selectById(id);
        if(initial==null) return false;
        var session=sessions.lockById(initial.getSessionId());
        var run=runs.lockById(id);
        return session!=null && run.getRunNo().equals(session.getActiveOperationId())
                && time.now().isBefore(run.getDeadlineAt()) && runs.claim(id, time.now())==1;
    }
    public void checkLive(AgentRun run) {
        var current=runs.selectById(run.getId());
        if(current==null || !"RUNNING".equals(current.getStatus())) throw new java.util.concurrent.CancellationException();
        if(!time.now().isBefore(current.getDeadlineAt()))
            throw BusinessException.conflict("AGENT_TIMEOUT", "导诊超过60秒，请重试或直接预约");
    }
    @Transactional(transactionManager="mysqlTransactionManager")
    public AgentStep beginStep(AgentRun initial, int round, int tools, String kind, String name, String callId, String label) {
        var session=sessions.lockById(initial.getSessionId());
        var run=runs.lockById(initial.getId());
        if(session==null || !initial.getRunNo().equals(session.getActiveOperationId())) throw new java.util.concurrent.CancellationException();
        checkLive(run);
        runs.progress(run.getId(), round, tools);
        var step=new AgentStep();
        step.setRunId(run.getId()); step.setSequenceNo(steps.listByRun(run.getId()).size()+1);
        step.setRoundNo(round); step.setStepType(kind); step.setToolName(name); step.setToolCallId(callId);
        step.setStatus("RUNNING"); step.setPublicLabel(label); step.setStartedAt(time.now());
        steps.insert(step);
        return step;
    }
    public void endStep(AgentStep step, String status, String output, String evidence, String code) {
        jdbc.update("UPDATE agent_step SET status=?,output_summary=?,evidence_chunk_ids=?,error_code=?,finished_at=?,duration_ms=? WHERE id=? AND status='RUNNING'",
                status, output, evidence, code, time.now(), Math.max(0,java.time.Duration.between(step.getStartedAt(), time.now()).toMillis()), step.getId());
        if (!"SUCCEEDED".equals(status)) {
            jdbc.update("UPDATE ai_call_log SET status=?,finished_at=?,error_code=?,error_message=? WHERE step_id=? AND status='RUNNING'",
                    "TIMEOUT".equals(status)?"TIMEOUT":"FAILED",time.now(),code,"本次查询未完成",step.getId());
        }
    }
    @Transactional(transactionManager="mysqlTransactionManager")
    public boolean publish(AgentRun initial, TriageResult result, String state, int rounds, int tools) {
        var session=sessions.lockById(initial.getSessionId());
        var run=runs.lockById(initial.getId());
        if(session==null || !initial.getRunNo().equals(session.getActiveOperationId())
                || !Set.of("RUNNING","QUEUED").contains(run.getStatus()) || !time.now().isBefore(run.getDeadlineAt())
                || !run.getSummaryVersion().equals(session.getSummaryVersion()) || session.getSummaryConfirmedAt()==null) return false;
        results.insert(result);
        runs.finish(run.getId(), state, rounds, tools, time.now(), null, null);
        sessions.finishTriage(run.getSessionId(), run.getRunNo(), result.getId());
        return true;
    }
    @Transactional(transactionManager="mysqlTransactionManager")
    public void fail(AgentRun initial, String state, String code, String message) {
        sessions.lockById(initial.getSessionId());
        var run=runs.lockById(initial.getId());
        if(run==null || runs.finish(run.getId(),state,run.getActualRounds(),run.getActualToolCalls(),time.now(),code,message)!=1) return;
        sessions.finishTriage(run.getSessionId(),run.getRunNo(),null);
        String stepStatus="AGENT_TIMEOUT".equals(code)?"TIMEOUT":"FAILED";
        jdbc.update("UPDATE agent_step SET status=?,finished_at=?,error_code=? WHERE run_id=? AND status='RUNNING'",stepStatus,time.now(),code,run.getId());
        jdbc.update("UPDATE ai_call_log SET status=?,finished_at=?,error_code=?,error_message=? WHERE run_id=? AND status='RUNNING'",
                "CANCELLED".equals(state)?"CANCELLED":"TIMEOUT".equals(stepStatus)?"TIMEOUT":"FAILED",time.now(),code,message,run.getId());
    }
    public List<AgentRun> expired() { return runs.expired(time.now()); }
    public List<AgentRun> interrupted() { return runs.interrupted(); }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
