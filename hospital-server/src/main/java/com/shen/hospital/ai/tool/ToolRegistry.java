package com.shen.hospital.ai.tool;

import com.fasterxml.jackson.databind.*;
import com.shen.hospital.ai.AiCallRecorder;
import com.shen.hospital.common.*;
import com.shen.hospital.masterdata.dto.DepartmentView;
import com.shen.hospital.masterdata.mapper.DepartmentMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 数据库驱动的只读工具注册中心：工具池来自 mcp_tool 启用状态，
 * 执行器编码必须是服务端白名单；每次执行写入 mcp_tool_call_log。
 */
@Service
public class ToolRegistry {
    public static final String KNOWLEDGE_TOOL = "medical_knowledge_retrieve";
    public static final String PROFILE_TOOL = "patient_profile_read";
    public static final String VISITS_TOOL = "visit_history_read";
    public static final String SYMPTOM_TOOL = "symptom_tag_search";
    public static final String DEPARTMENTS_TOOL = "department_search";
    public static final String SCHEDULES_TOOL = "doctor_schedule_search";
    /** 服务端已实现并测试过的执行器白名单；登记未知执行器会被拒绝。 */
    public static final Set<String> EXECUTORS = Set.of(KNOWLEDGE_TOOL, PROFILE_TOOL, VISITS_TOOL,
            SYMPTOM_TOOL, DEPARTMENTS_TOOL, SCHEDULES_TOOL);
    public static final Map<String,String> PUBLIC_LABELS = Map.of(
            KNOWLEDGE_TOOL, "正在检索导诊知识",
            PROFILE_TOOL, "正在读取本人档案",
            VISITS_TOOL, "正在读取本人就诊摘要",
            SYMPTOM_TOOL, "正在查询症状标签",
            DEPARTMENTS_TOOL, "正在查询启用科室",
            SCHEDULES_TOOL, "正在查询可预约号源");

    private final McpToolMapper tools;
    private final ToolCallLogMapper logs;
    private final MedicalToolService medical;
    private final PatientContextTools patientTools;
    private final SymptomTagTools symptomTools;
    private final DepartmentMapper departments;
    private final ObjectMapper json;
    private final BusinessTime time;

    public ToolRegistry(McpToolMapper tools, ToolCallLogMapper logs, MedicalToolService medical,
                        PatientContextTools patientTools, SymptomTagTools symptomTools,
                        DepartmentMapper departments, ObjectMapper json, BusinessTime time) {
        this.tools = tools; this.logs = logs; this.medical = medical; this.patientTools = patientTools;
        this.symptomTools = symptomTools; this.departments = departments; this.json = json; this.time = time;
    }

    public String spaceKey() { return medical.spaceKey(); }
    public boolean knowledgeAvailable() { return medical.knowledgeAvailable(); }
    public List<DepartmentView> directory() { return medical.directory(); }
    public JsonNode finalSchedules(String departmentId, LocalDate today) { return medical.finalSchedules(departmentId, today); }

    public List<McpTool> enabled() { return tools.enabled(); }

    public List<String> enabledCodes() { return tools.enabled().stream().map(McpTool::getCode).toList(); }

    public List<ToolSpecification> specifications() {
        var result = new ArrayList<ToolSpecification>();
        for (McpTool tool : tools.enabled()) {
            try { result.add(toSpecification(tool)); }
            catch (RuntimeException ex) { throw new IllegalStateException("工具 " + tool.getCode() + " 的Schema不合法", ex); }
        }
        return List.copyOf(result);
    }

    public boolean enabled(String code) {
        var tool = tools.byCode(code);
        return tool != null && "ENABLED".equals(tool.getStatus());
    }

    /** 执行一次工具：执行前重新检查启用状态，并把输入输出摘要写入调用日志。 */
    public JsonNode execute(String code, String arguments, Long patientId, Long actorUserId, String traceId) {
        McpTool tool = tools.byCode(code);
        if (tool == null) throw BusinessException.missing("导诊工具");
        if (!"ENABLED".equals(tool.getStatus()))
            throw new BusinessException(HttpStatus.CONFLICT, "TOOL_DISABLED", "工具已停用，本次未执行");
        JsonNode input = parseArguments(arguments);
        var context = AiCallRecorder.currentContext();
        ToolCallLog log = startLog(tool, input, context, actorUserId, traceId);
        long started = System.nanoTime();
        try {
            JsonNode output = dispatch(tool, input, patientId, traceId);
            String summary = clip(output.toString(), 4000);
            logs.finish(log.getId(), "SUCCEEDED", time.now(), elapsed(started), summary, null);
            return output;
        } catch (BusinessException ex) {
            logs.finish(log.getId(), "FAILED", time.now(), elapsed(started), null, ex.code());
            throw ex;
        } catch (RuntimeException ex) {
            logs.finish(log.getId(), "FAILED", time.now(), elapsed(started), null, "TOOL_EXECUTION_FAILED");
            throw ex;
        }
    }

    private JsonNode dispatch(McpTool tool, JsonNode input, Long patientId, String traceId) {
        return switch (tool.getExecutorCode()) {
            case KNOWLEDGE_TOOL -> medical.search(reText(input), traceId);
            case DEPARTMENTS_TOOL -> medical.findDepartments(reText(input));
            case SCHEDULES_TOOL -> medical.findSchedules(reText(input));
            case PROFILE_TOOL -> { rejectIdentityFields(input); yield patientTools.profile(patientId); }
            case VISITS_TOOL -> { rejectIdentityFields(input); yield patientTools.visits(patientId); }
            case SYMPTOM_TOOL -> symptomTools.search(reText(input).path("query").asText(null));
            default -> throw BusinessException.missing("工具执行器");
        };
    }

    /** 患者身份工具不接受任何身份字段，身份只能来自运行上下文注入。 */
    private static void rejectIdentityFields(JsonNode input) {
        for (String field : List.of("patientId", "userId", "profileId", "patient_id", "user_id"))
            if (input.has(field)) throw BusinessException.invalid("工具不接受参数：" + field);
    }

    private ToolCallLog startLog(McpTool tool, JsonNode input, AiCallRecorder.Context context, Long actorUserId, String traceId) {
        var row = new ToolCallLog();
        row.setRunId(context == null ? null : context.runId());
        row.setStepId(context == null ? null : context.stepId());
        row.setToolId(tool.getId());
        row.setToolCodeSnapshot(tool.getCode());
        row.setToolRevision(tool.getRevision());
        row.setActorUserId(actorUserId);
        row.setCallSource(context == null ? "ADMIN_TEST" : "AGENT");
        row.setInputRedacted(clip(input.toString(), 1000));
        row.setStatus("RUNNING");
        row.setStartedAt(time.now());
        row.setTraceId(traceId);
        logs.insert(row);
        return row;
    }

    private JsonNode parseArguments(String arguments) {
        try {
            JsonNode node = json.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
            if (node == null || !node.isObject() || (arguments != null && arguments.length() > 5000))
                throw BusinessException.invalid("工具参数必须是JSON对象");
            return node;
        } catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw BusinessException.invalid("工具参数不是合法JSON"); }
    }

    /** 重新序列化输入，避免调用方传入的对象被执行器原地修改。 */
    private JsonNode reText(JsonNode input) {
        try { return json.readTree(input.toString()); }
        catch (Exception ex) { throw BusinessException.invalid("工具参数不合法"); }
    }

    public ToolSpecification toSpecification(McpTool tool) {
        JsonNode schema = parseSchema(tool.getInputSchema());
        var builder = JsonObjectSchema.builder();
        JsonNode properties = schema.path("properties");
        if (properties.isObject()) {
            var fields = new ArrayList<String>();
            properties.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                JsonNode property = properties.path(field);
                String description = property.path("description").asText(null);
                switch (property.path("type").asText("string")) {
                    case "integer" -> { if (description == null) builder.addIntegerProperty(field); else builder.addIntegerProperty(field, description); }
                    case "number" -> { if (description == null) builder.addNumberProperty(field); else builder.addNumberProperty(field, description); }
                    case "boolean" -> { if (description == null) builder.addBooleanProperty(field); else builder.addBooleanProperty(field, description); }
                    default -> { if (description == null) builder.addStringProperty(field); else builder.addStringProperty(field, description); }
                }
            }
        }
        var required = new ArrayList<String>();
        for (JsonNode item : schema.path("required")) if (item.isTextual()) required.add(item.asText());
        if (!required.isEmpty()) builder.required(required.toArray(String[]::new));
        return ToolSpecification.builder().name(tool.getCode()).description(tool.getDescription())
                .parameters(builder.build()).build();
    }

    private JsonNode parseSchema(String schema) {
        try {
            JsonNode node = json.readTree(schema == null ? "{}" : schema);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("not object");
            return node;
        } catch (Exception ex) { throw BusinessException.invalid("工具Schema必须是JSON对象"); }
    }

    public String clip(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static long elapsed(long startedNanos) { return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000); }

    public record ToolView(String id, String code, String name, String description, JsonNode inputSchema,
                           String executorCode, String status, Integer timeoutMs, Integer maxResultChars,
                           Integer sortNo, Integer revision, LocalDateTime updatedAt) {
        public static ToolView of(McpTool tool, ObjectMapper json) {
            JsonNode schema;
            try { schema = json.readTree(tool.getInputSchema()); }
            catch (Exception ex) { schema = json.createObjectNode(); }
            return new ToolView(tool.getId().toString(), tool.getCode(), tool.getName(), tool.getDescription(),
                    schema, tool.getExecutorCode(), tool.getStatus(), tool.getTimeoutMs(), tool.getMaxResultChars(),
                    tool.getSortNo(), tool.getRevision(), tool.getUpdatedAt());
        }
    }
}
