package com.shen.hospital.ai.agent;

import com.fasterxml.jackson.databind.*;
import com.shen.hospital.ai.*;
import com.shen.hospital.ai.prompt.PromptService;
import com.shen.hospital.ai.tool.ToolRegistry;
import com.shen.hospital.common.*;
import com.shen.hospital.consultation.dto.ConfirmedSummary;
import com.shen.hospital.consultation.mapper.ConsultationSessionMapper;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

@Service
public class TriageAgentService {
    private final AgentStore store;
    private final ToolRegistry tools;
    private final HospitalAiService ai;
    private final ModelFactory models;
    private final AgentResultValidator validator;
    private final ConsultationSessionMapper sessions;
    private final PromptService prompts;
    private final ObjectMapper json;
    private final BusinessTime time;
    private final ThreadPoolTaskExecutor executor;
    private final ExecutorService io;
    private final com.shen.hospital.knowledge.service.KnowledgeService knowledge;
    private final Map<Long,Future<?>> calls=new ConcurrentHashMap<>();

    public TriageAgentService(AgentStore store, ToolRegistry tools, HospitalAiService ai, ModelFactory models,
                              AgentResultValidator validator, ConsultationSessionMapper sessions, PromptService prompts,
                              ObjectMapper json, BusinessTime time, @Qualifier("agentTaskExecutor") ThreadPoolTaskExecutor executor,
                              @Qualifier("agentIoExecutor") ExecutorService io,
                              com.shen.hospital.knowledge.service.KnowledgeService knowledge) {
        this.store=store; this.tools=tools; this.ai=ai; this.models=models; this.validator=validator;
        this.sessions=sessions; this.prompts=prompts; this.json=json; this.time=time; this.executor=executor; this.io=io; this.knowledge=knowledge;
    }
    public record RunView(String id,String sessionId,String status,int actualRounds,int actualToolCalls,String resultId,
                          String errorCode,String errorMessage,List<StepView> steps,LocalDateTime createdAt) {}
    public record StepView(int sequenceNo,int roundNo,String stepType,String toolName,String status,String publicLabel,Long durationMs) {}
    public record ResultView(String id,String sessionId,String riskLevel,String validationStatus,String departmentId,
                             String departmentName,JsonNode alternativeDepartmentIds,String reason,JsonNode evidenceRefs,
                             JsonNode doctorOptions,JsonNode missingInformation,LocalDateTime expiresAt,
                             boolean isCurrent,boolean canBook,String unavailableReason) {}
    public record Created(RunView run,long pollAfterMs) {}
    public record EventItem(int seq,String type,String label,String stepType,String status,Long durationMs,LocalDateTime createdAt) {}
    public record EventsView(String runId,String status,int latestSeq,int cursor,List<EventItem> items) {}
    /** 管理员运行详情：在患者视图之上补充版本与快照，用于回放定位。 */
    public record AdminRunView(RunView run,String runNo,String modelName,String configFingerprint,String promptHash,
                               Long promptRevisionId,JsonNode toolNames,JsonNode knowledgeSnapshot,String safetyRuleVersion,
                               int maxRounds,int maxToolCalls,LocalDateTime startedAt,LocalDateTime finishedAt,
                               LocalDateTime deadlineAt,String traceId) {}

    public Created create(Long userId,Long sessionId,int version,String clientId,String traceId) {
        if(clientId==null || clientId.isBlank() || clientId.length()>64) throw BusinessException.invalid("请求号不合法");
        // 工具池与 Prompt 均在创建时取得快照：运行中固定，管理员修改从下一次新运行生效。
        var prompt=prompts.current(PromptService.TRIAGE_AGENT);
        var promptRevision=prompts.currentRevisionRowId(PromptService.TRIAGE_AGENT);
        // AgentStore returns only after the MySQL transaction commits.
        var accepted=knowledge.admitAgent(() -> store.create(userId,sessionId,version,clientId,traceId,
                prompt,promptRevision,tools.enabledCodes(),tools.spaceKey(),tools.knowledgeAvailable(),knowledge.maintenance()));
        if(accepted.created()) {
            try { executor.execute(() -> process(accepted.run())); }
            catch(RejectedExecutionException ex) { store.fail(accepted.run(),"FAILED","AGENT_QUEUE_FULL","导诊任务队列已满，请稍后重试"); }
        }
        return new Created(view(store.owned(userId,accepted.run().getId())),2000);
    }
    public RunView get(Long userId,Long id) { return view(store.owned(userId,id)); }
    public PageResult<RunView> history(Long userId,Long sessionId,long page,long size) {
        var list=store.history(userId,sessionId,page,size);
        return new PageResult<>(list.items().stream().map(this::view).toList(),list.total(),list.page(),list.pageSize());
    }
    public PageResult<RunView> adminList(String status,long page,long size) {
        var list=store.list(status,page,size);
        return new PageResult<>(list.items().stream().map(this::view).toList(),list.total(),list.page(),list.pageSize());
    }
    public RunView adminDetail(Long id) { return view(store.detail(id)); }
    /** 管理员回放视图：模型/Prompt/工具/知识版本快照与预算，一次执行可定位失败来源。 */
    public AdminRunView adminReplay(Long id) {
        var run=store.detail(id);
        return new AdminRunView(view(run),run.getRunNo(),run.getModelName(),run.getConfigFingerprint(),run.getPromptHash(),
                run.getPromptRevisionId(),parse(run.getToolNames()),parse(run.getKnowledgeSnapshot()),run.getSafetyRuleVersion(),
                run.getMaxRounds(),run.getMaxToolCalls(),run.getStartedAt(),run.getFinishedAt(),run.getDeadlineAt(),run.getTraceId());
    }
    /** 患者事件订阅：cursor 之后的公开事件按序返回；断线重连从上次序号补齐，不重复不发新任务。 */
    public EventsView events(Long userId,Long id,int cursor) {
        var run=store.owned(userId,id);
        var items=store.eventsAfter(run.getId(),cursor).stream()
                .map(e -> { var payload=parse(e.getPayload());
                    return new EventItem(e.getSequenceNo(),e.getEventType(),payload.path("label").asText(""),
                            payload.path("stepType").asText(null),payload.path("status").asText(null),
                            payload.path("durationMs").isNumber()?payload.path("durationMs").asLong():null,e.getCreatedAt()); })
                .toList();
        return new EventsView(run.getId().toString(),run.getStatus(),store.latestEventSeq(run.getId()),Math.max(cursor,0),items);
    }
    private RunView view(AgentRun run) {
        var result=store.byRun(run.getId());
        return new RunView(run.getId().toString(),run.getSessionId().toString(),run.getStatus(),run.getActualRounds(),run.getActualToolCalls(),
                result==null?null:result.getId().toString(),run.getErrorCode(),run.getErrorMessage(),
                store.steps(run.getId()).stream().map(s -> new StepView(s.getSequenceNo(),s.getRoundNo(),s.getStepType(),s.getToolName(),s.getStatus(),s.getPublicLabel(),s.getDurationMs())).toList(),run.getCreatedAt());
    }
    public ResultView result(Long userId,Long id) {
        var r=store.ownedResult(userId,id);
        var session=sessions.findOwned(r.getSessionId(),r.getPatientId());
        boolean current=session!=null && id.equals(session.getLatestResultId())
                && r.getSummaryVersion().equals(session.getSummaryVersion()) && session.getSummaryConfirmedAt()!=null;
        var department=tools.directory().stream().filter(d -> r.getDepartmentId()!=null && d.id().equals(r.getDepartmentId().toString())).findFirst();
        String unavailable=session==null?"问诊记录已删除，此结果仅供历史查阅":!current?"此为历史结果，请重新确认症状并导诊":!r.getExpiresAt().isAfter(time.now())?"导诊结果已过期":
                !"ROUTINE".equals(r.getRiskLevel())?"该风险等级不支持关联普通预约":!"VALID".equals(r.getValidationStatus())?"信息或证据尚不足":
                department.isEmpty()||department.get().isEmergency()?"推荐科室当前不可预约":"";
        return new ResultView(r.getId().toString(),r.getSessionId().toString(),r.getRiskLevel(),r.getValidationStatus(),
                r.getDepartmentId()==null?null:r.getDepartmentId().toString(),department.map(d -> d.name()).orElse(""),
                parse(r.getAlternativeDepartmentIds()),r.getReason(),parse(r.getEvidenceRefs()),parse(r.getDoctorOptionsSnapshot()),
                parse(r.getMissingInformation()),r.getExpiresAt(),current,unavailable.isEmpty(),unavailable);
    }
    public boolean cancel(Long userId,Long id) {
        var run=store.owned(userId,id);
        if(!Set.of("QUEUED","RUNNING").contains(run.getStatus())) return false;
        store.fail(run,"CANCELLED","CLIENT_CANCELLED","已停止本次导诊");
        var call=calls.get(id); if(call!=null)call.cancel(true);
        return true;
    }
    public void recover() { store.interrupted().forEach(r -> store.fail(r,"FAILED","AGENT_INTERRUPTED","上次导诊未完成，请重新开始")); }
    @Scheduled(fixedDelay=5000)
    public void expire() {
        for(var run:store.expired()) {
            store.fail(run,"FAILED","AGENT_TIMEOUT","导诊超过60秒，请重试或直接预约");
            var call=calls.get(run.getId()); if(call!=null)call.cancel(true);
        }
    }

    void process(AgentRun run) {
        if(!store.claim(run.getId()))return;
        try {
            var summary=store.summary(parse(run.getInputSnapshot()).path("summary").toString());
            if(summary.emergency()) {
                var step=store.beginStep(run,0,0,"SAFETY",null,null,"已识别患者确认的危险信号");
                store.endStep(step,"SUCCEEDED","{\"riskLevel\":\"EMERGENCY\"}","[]",null);
                store.publish(run,validator.emergency(run),"SUCCEEDED",0,0);
                return;
            }
            var toolCodes=new java.util.HashSet<String>();
            for(var code:parse(run.getToolNames())) if(code.isTextual()) toolCodes.add(code.asText());
            var history=new ArrayList<ChatMessage>();
            history.add(SystemMessage.from(promptOf(run) + "\n医院当前日期：" + time.today()));
            history.add(UserMessage.from(run.getInputSnapshot()));
            var evidence=new LinkedHashMap<String,JsonNode>();
            var callIds=new HashSet<String>();
            int toolCount=0;
            for(int round=1;round<=run.getMaxRounds();round++) {
                store.checkLive(run);
                var step=store.beginStep(run,round,toolCount,"MODEL",null,null,"正在分析已确认的症状摘要");
                var request=ChatRequest.builder().messages(history).toolSpecifications(tools.specifications()).maxOutputTokens(1024).build();
                dev.langchain4j.model.chat.response.ChatResponse response;
                try {
                    response=bounded(run,step,models.config().timeoutSeconds(),() -> ai.chatWithTools(request,"AGENT",run.getTraceId()));
                    store.endStep(step,"SUCCEEDED","{\"received\":true}","[]",null);
                } catch(Exception ex) { store.endStep(step,"FAILED",null,"[]",code(ex)); throw ex; }
                history.add(response.aiMessage());
                var requests=response.aiMessage().toolExecutionRequests();
                if(requests==null || requests.isEmpty()) {
                    var check=store.beginStep(run,round,toolCount,"VALIDATION",null,null,"正在核对科室、引用及可预约号源");
                    try {
                        var directory=bounded(run,check,3,tools::directory);
                        var result=validator.validate(run,summary,parseCandidate(response.aiMessage().text()),evidence,directory);
                        if(result.getDepartmentId()!=null && "ROUTINE".equals(result.getRiskLevel()) && "VALID".equals(result.getValidationStatus())) {
                            result.setDoctorOptionsSnapshot(bounded(run,check,3,() -> tools.finalSchedules(result.getDepartmentId().toString(),time.today())).toString());
                        }
                        store.endStep(check,"SUCCEEDED","{\"validated\":true}",json.valueToTree(evidence.keySet()).toString(),null);
                        store.publish(run,result,"VALID".equals(result.getValidationStatus())?"SUCCEEDED":"DEGRADED",round,toolCount);
                    } catch(Exception ex) { store.endStep(check,"FAILED",null,"[]",code(ex)); throw ex; }
                    return;
                }
                if(requests.size()>run.getMaxToolCalls()-toolCount) throw budget();
                for(var call:requests) {
                    if(call.id()==null || call.id().isBlank() || call.id().length()>100 || !callIds.add(call.id())
                            || !toolCodes.contains(call.name())) throw invalid();
                    toolCount++;
                    var toolStep=store.beginStep(run,round,toolCount,"TOOL",call.name(),call.id(),label(call.name()));
                    JsonNode output;
                    try {
                        if(!tools.enabled(call.name())) {
                            // 运行中被管理员停用：不执行业务查询，向模型返回 TOOL_DISABLED。
                            output=json.createObjectNode().put("errorCode","TOOL_DISABLED").put("message","工具已停用，本次未执行");
                            store.endStep(toolStep,"FAILED",output.toString(),"[]","TOOL_DISABLED");
                        } else {
                            output=bounded(run,toolStep,ToolRegistry.KNOWLEDGE_TOOL.equals(call.name())?30:3,
                                    () -> tools.execute(call.name(),call.arguments(),run.getPatientId(),null,run.getTraceId()));
                            if(ToolRegistry.KNOWLEDGE_TOOL.equals(call.name())) {
                                if(!tools.spaceKey().equals(output.path("spaceKey").asText())) throw invalid();
                                for(var hit:output.path("hits")) evidence.put(hit.path("chunkId").asText(),hit.deepCopy());
                            }
                            store.endStep(toolStep,"SUCCEEDED",output.toString(),json.valueToTree(evidence.keySet()).toString(),null);
                        }
                    } catch(BusinessException ex) {
                        if("AGENT_TIMEOUT".equals(ex.code())) throw ex;
                        output=json.createObjectNode().put("errorCode",ex.code()).put("message",ex.getMessage());
                        store.endStep(toolStep,"FAILED",output.toString(),"[]",ex.code());
                    } catch(TimeoutException ex) {
                        output=json.createObjectNode().put("errorCode","TOOL_TIMEOUT").put("message","查询超时，请不要猜测结果");
                        store.endStep(toolStep,"TIMEOUT",output.toString(),"[]","TOOL_TIMEOUT");
                    }
                    history.add(ToolExecutionResultMessage.from(call,output.toString()));
                }
            }
            throw budget();
        } catch(CancellationException ex) {
            store.fail(run,"CANCELLED","CLIENT_CANCELLED","已停止本次导诊");
        } catch(Exception ex) {
            store.fail(run,"FAILED",code(ex),ex instanceof BusinessException ? ex.getMessage():"导诊处理失败，请稍后重试或直接预约");
        }
    }
    private String promptOf(AgentRun run) {
        // 运行固定创建时快照的 Prompt 内容，发布新修订不影响已开始的运行。
        return run.getPromptContent()==null ? prompts.current(PromptService.TRIAGE_AGENT) : run.getPromptContent();
    }
    private <T> T bounded(AgentRun run,AgentStep step,int maxSeconds,Supplier<T> action) throws Exception {
        store.checkLive(run);
        long remaining=Duration.between(time.now(),run.getDeadlineAt()).toMillis();
        if(remaining<=0) throw timeout();
        Future<T> call=io.submit(() -> { store.checkLive(run); return AiCallRecorder.withContext(run.getId(),step.getId(),action); });
        calls.put(run.getId(),call);
        try {
            T output=call.get(Math.min(remaining,maxSeconds*1000L),TimeUnit.MILLISECONDS);
            store.checkLive(run); return output;
        } catch(ExecutionException ex) {
            if(ex.getCause() instanceof Exception error)throw error;
            throw new IllegalStateException(ex.getCause());
        } catch(TimeoutException ex) { call.cancel(true); if(!time.now().isBefore(run.getDeadlineAt()))throw timeout(); throw ex; }
        finally { calls.remove(run.getId(),call); if(!call.isDone())call.cancel(true); }
    }
    private JsonNode parseCandidate(String value) {
        if(value==null || value.length()>12000)throw invalid();
        try { var node=json.readTree(value); if(node==null||!node.isObject())throw invalid(); return node; }
        catch(Exception ex){throw invalid();}
    }
    private JsonNode parse(String value) { try { return json.readTree(value); } catch(Exception ex){throw invalid();} }
    private static BusinessException invalid() { return BusinessException.conflict("AI_OUTPUT_INVALID","模型结果未通过校验，请重试或直接预约"); }
    private static BusinessException timeout() { return BusinessException.conflict("AGENT_TIMEOUT","导诊超过60秒，请重试或直接预约"); }
    private static BusinessException budget() { return BusinessException.conflict("AGENT_BUDGET_EXCEEDED","导诊达到6轮或12次工具查询限制，请补充信息后重试"); }
    private static String code(Exception ex) { return ex instanceof BusinessException b?b.code():ex instanceof TimeoutException?"AGENT_TIMEOUT":"AGENT_FAILED"; }
    private static String label(String name) { return ToolRegistry.PUBLIC_LABELS.getOrDefault(name,"正在查询导诊信息"); }
}
