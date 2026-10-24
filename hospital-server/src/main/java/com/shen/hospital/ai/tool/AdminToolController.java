package com.shen.hospital.ai.tool;

import com.shen.hospital.auth.security.CurrentUser;
import com.shen.hospital.common.*;
import com.shen.hospital.masterdata.mapper.PatientProfileMapper;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 管理端工具中心：登记、启停、测试与调用日志；执行器为服务端白名单。 */
@RestController
@RequestMapping("/api/admin")
public class AdminToolController {
    private final ToolRegistry registry;
    private final McpToolMapper tools;
    private final ToolCallLogMapper logs;
    private final PatientProfileMapper patients;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public AdminToolController(ToolRegistry registry, McpToolMapper tools, ToolCallLogMapper logs,
                               PatientProfileMapper patients, com.fasterxml.jackson.databind.ObjectMapper json) {
        this.registry = registry; this.tools = tools; this.logs = logs; this.patients = patients; this.json = json;
    }

    public record ToolRow(String id, String code, String name, String description, Object inputSchema,
                          String executorCode, String status, Integer timeoutMs, Integer maxResultChars,
                          Integer sortNo, Integer revision, LocalDateTime updatedAt) {}

    @GetMapping("/tools")
    public ApiResponse<List<ToolRow>> list() {
        return ApiResponse.ok(tools.all().stream().map(t -> new ToolRow(t.getId().toString(), t.getCode(), t.getName(),
                t.getDescription(), schema(t.getInputSchema()), t.getExecutorCode(), t.getStatus(), t.getTimeoutMs(),
                t.getMaxResultChars(), t.getSortNo(), t.getRevision(), t.getUpdatedAt())).toList());
    }

    @GetMapping("/tool-executors")
    public ApiResponse<Map<String,Object>> executors() {
        return ApiResponse.ok(Map.of("executors", ToolRegistry.EXECUTORS.stream().sorted().toList(),
                "note", "仅可登记服务端已实现并测试过的执行器；本系统为本地工具注册，不宣称已连接远程 MCP 服务"));
    }

    public record ToolRequest(@NotBlank @Size(max=100) String code, @NotBlank @Size(max=100) String name,
                              @NotBlank @Size(max=2000) String description, @NotNull String inputSchema,
                              @NotBlank @Size(max=100) String executorCode,
                              @Min(1000) @Max(60000) Integer timeoutMs, @Min(500) @Max(20000) Integer maxResultChars) {}
    @PostMapping("/tools")
    public ApiResponse<ToolRow> create(@org.springframework.validation.annotation.Validated ToolRequest request) {
        if (!ToolRegistry.EXECUTORS.contains(request.executorCode()))
            throw BusinessException.invalid("未知执行器，仅可选择服务端已实现的执行器");
        if (tools.byCode(request.code().strip()) != null) throw BusinessException.conflict("TOOL_CODE_EXISTS", "工具编码已存在");
        validateSchema(request.inputSchema());
        var tool = new McpTool();
        tool.setCode(request.code().strip()); tool.setName(request.name().strip());
        tool.setDescription(request.description().strip()); tool.setInputSchema(request.inputSchema().strip());
        tool.setExecutorCode(request.executorCode()); tool.setStatus("ENABLED");
        tool.setTimeoutMs(request.timeoutMs() == null ? 3000 : request.timeoutMs());
        tool.setMaxResultChars(request.maxResultChars() == null ? 4000 : request.maxResultChars());
        tool.setSortNo((int)(tools.selectCount(null) + 1)); tool.setRevision(1);
        tools.insert(tool);
        return ApiResponse.ok(row(tools.byCode(tool.getCode())));
    }

    public record ToolUpdate(@Size(max=100) String name, @Size(max=2000) String description,
                             String inputSchema, @Min(1000) @Max(60000) Integer timeoutMs,
                             @Min(500) @Max(20000) Integer maxResultChars) {}
    @PatchMapping("/tools/{id}")
    public ApiResponse<ToolRow> update(@PathVariable @Positive Long id,
                                       @org.springframework.validation.annotation.Validated ToolUpdate request) {
        var tool = owned(id);
        var update = new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<McpTool>()
                .eq(McpTool::getId, id).set(McpTool::getRevision, tool.getRevision() + 1);
        if (request.name() != null) update.set(McpTool::getName, request.name().strip());
        if (request.description() != null) update.set(McpTool::getDescription, request.description().strip());
        if (request.inputSchema() != null) { validateSchema(request.inputSchema()); update.set(McpTool::getInputSchema, request.inputSchema().strip()); }
        if (request.timeoutMs() != null) update.set(McpTool::getTimeoutMs, request.timeoutMs());
        if (request.maxResultChars() != null) update.set(McpTool::getMaxResultChars, request.maxResultChars());
        tools.update(null, update);
        return ApiResponse.ok(row(tools.selectById(id)));
    }

    public record StatusRequest(@NotBlank @Pattern(regexp = "ENABLED|DISABLED") String status) {}
    @PostMapping("/tools/{id}/status")
    public ApiResponse<ToolRow> flip(@PathVariable @Positive Long id, @org.springframework.validation.annotation.Validated StatusRequest request) {
        var tool = owned(id);
        if (tools.flipStatus(id, request.status()) != 1)
            throw BusinessException.conflict("TOOL_STATUS_UNCHANGED", "工具状态未变化，请刷新后重试");
        return ApiResponse.ok(row(tools.selectById(id)));
    }

    /** 工具测试：使用管理员授权的测试入参；患者上下文工具可指定测试患者，日志记为 ADMIN_TEST。 */
    public record ToolTestRequest(String arguments, Long patientId) {}
    public record ToolTestResult(Object output, long durationMs, String status, String errorCode) {}
    @PostMapping("/tools/{id}/test")
    public ApiResponse<ToolTestResult> test(@PathVariable @Positive Long id,
                                            @RequestBody(required = false) ToolTestRequest request) {
        var tool = owned(id);
        Long patientId = request == null || request.patientId() == null ? null : request.patientId();
        if (patientId != null && patients.selectById(patientId) == null) throw BusinessException.missing("测试患者档案");
        long started = System.nanoTime();
        try {
            var output = registry.execute(tool.getCode(), request == null ? "{}" : request.arguments(),
                    patientId, CurrentUser.get().id(), "admin-test");
            return ApiResponse.ok(new ToolTestResult(output, elapsed(started), "SUCCEEDED", null));
        } catch (BusinessException ex) {
            return ApiResponse.ok(new ToolTestResult(Map.of("errorCode", ex.code(), "message", ex.getMessage()),
                    elapsed(started), "FAILED", ex.code()));
        }
    }

    @GetMapping("/tool-call-logs")
    public ApiResponse<PageResult<ToolCallRow>> callLogs(@RequestParam(required=false) String code,
            @RequestParam(required=false) @Pattern(regexp="RUNNING|SUCCEEDED|FAILED") String status,
            @RequestParam(required=false) @Positive Long runId,
            @RequestParam(defaultValue="1") @Min(1) long page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) long pageSize) {
        var rows = logs.filtered(code, status, runId);
        int from = (int) Math.min(rows.size(), (page - 1) * pageSize);
        int to = (int) Math.min(rows.size(), from + pageSize);
        return ApiResponse.ok(new PageResult<>(rows.subList(from, to).stream().map(this::row).toList(),
                rows.size(), page, pageSize));
    }

    public record ToolCallRow(String id, Long runId, Long stepId, String toolCode, Integer toolRevision,
                              String callSource, Object input, Object output, String status, Long durationMs,
                              String errorCode, String traceId, LocalDateTime createdAt) {}
    private ToolCallRow row(ToolCallLog log) {
        return new ToolCallRow(log.getId().toString(), log.getRunId(), log.getStepId(), log.getToolCodeSnapshot(),
                log.getToolRevision(), log.getCallSource(), schema(log.getInputRedacted()), schema(log.getOutputSummary()),
                log.getStatus(), log.getDurationMs(), log.getErrorCode(), log.getTraceId(), log.getCreatedAt());
    }

    private McpTool owned(Long id) {
        var tool = tools.selectById(id);
        if (tool == null) throw BusinessException.missing("导诊工具");
        return tool;
    }

    private Object schema(String value) {
        try { return value == null ? null : json.readTree(value); }
        catch (Exception ex) { return value; }
    }

    private void validateSchema(String schema) {
        try {
            var node = json.readTree(schema);
            if (node == null || !node.isObject() || !node.path("properties").isObject())
                throw new IllegalArgumentException("schema");
            for (var required : node.path("required")) if (!required.isTextual()) throw new IllegalArgumentException("required");
        } catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw BusinessException.invalid("inputSchema 必须是含 properties 对象的合法 JSON"); }
    }

    private ToolRow row(McpTool tool) {
        return new ToolRow(tool.getId().toString(), tool.getCode(), tool.getName(), tool.getDescription(),
                schema(tool.getInputSchema()), tool.getExecutorCode(), tool.getStatus(), tool.getTimeoutMs(),
                tool.getMaxResultChars(), tool.getSortNo(), tool.getRevision(), tool.getUpdatedAt());
    }

    private static long elapsed(long startedNanos) { return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000); }
}
