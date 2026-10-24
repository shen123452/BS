package com.shen.hospital.ai.agent;

import com.shen.hospital.auth.security.CurrentUser;
import com.shen.hospital.common.ApiResponse;
import com.shen.hospital.common.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/patient")
public class PatientTriageController {
    private final TriageAgentService agent;
    public PatientTriageController(TriageAgentService agent) { this.agent=agent; }
    public record CreateRequest(@NotNull @Positive Long sessionId, @Min(0) int summaryVersion,
                                @NotBlank @Size(max=64) String clientRequestId) {}
    @PostMapping("/triage-runs")
    public ResponseEntity<ApiResponse<TriageAgentService.Created>> create(@Valid @RequestBody CreateRequest request) {
        return ResponseEntity.accepted().body(ApiResponse.ok(agent.create(CurrentUser.get().id(),request.sessionId(),
                request.summaryVersion(),request.clientRequestId(),MDC.get("traceId"))));
    }
    @GetMapping("/triage-runs/{id}")
    public ApiResponse<TriageAgentService.RunView> get(@PathVariable @Positive Long id) {
        return ApiResponse.ok(agent.get(CurrentUser.get().id(),id));
    }
    /** 事件订阅：cursor 之后的公开事件按序补齐；前端按 seq 去重，断线重连不丢事件也不发新任务。 */
    @GetMapping("/triage-runs/{id}/events")
    public ApiResponse<TriageAgentService.EventsView> events(@PathVariable @Positive Long id,
            @RequestParam(defaultValue="0") @Min(0) int cursor) {
        return ApiResponse.ok(agent.events(CurrentUser.get().id(),id,cursor));
    }
    @GetMapping("/consultations/{id}/triage-runs")
    public ApiResponse<PageResult<TriageAgentService.RunView>> history(@PathVariable @Positive Long id,
            @RequestParam(defaultValue="1") @Min(1) long page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) long pageSize) {
        return ApiResponse.ok(agent.history(CurrentUser.get().id(),id,page,pageSize));
    }
    @PostMapping("/triage-runs/{id}/cancel")
    public ApiResponse<Map<String,Boolean>> cancel(@PathVariable @Positive Long id) {
        return ApiResponse.ok(Map.of("cancelled",agent.cancel(CurrentUser.get().id(),id)));
    }
    @GetMapping("/triage-results/{id}")
    public ApiResponse<TriageAgentService.ResultView> result(@PathVariable @Positive Long id) {
        return ApiResponse.ok(agent.result(CurrentUser.get().id(),id));
    }
}
