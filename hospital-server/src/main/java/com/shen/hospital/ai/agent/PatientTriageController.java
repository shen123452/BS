package com.shen.hospital.ai.agent;

import com.shen.hospital.auth.security.CurrentUser;
import com.shen.hospital.common.ApiResponse;
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
    @PostMapping("/triage-runs/{id}/cancel")
    public ApiResponse<Map<String,Boolean>> cancel(@PathVariable @Positive Long id) {
        return ApiResponse.ok(Map.of("cancelled",agent.cancel(CurrentUser.get().id(),id)));
    }
    @GetMapping("/triage-results/{id}")
    public ApiResponse<TriageAgentService.ResultView> result(@PathVariable @Positive Long id) {
        return ApiResponse.ok(agent.result(CurrentUser.get().id(),id));
    }
}
