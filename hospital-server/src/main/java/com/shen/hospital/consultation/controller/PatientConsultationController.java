package com.shen.hospital.consultation.controller;

import com.shen.hospital.auth.security.CurrentUser;
import com.shen.hospital.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.shen.hospital.consultation.dto.*;
import com.shen.hospital.consultation.service.*;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/patient/consultations")
public class PatientConsultationController {
    private final ConsultationService consultations;
    private final ConsultationStreamService streams;
    public PatientConsultationController(ConsultationService consultations,ConsultationStreamService streams) {
        this.consultations=consultations;this.streams=streams;
    }
    @PostMapping public ApiResponse<SessionView> create(@Valid @RequestBody(required=false) CreateSessionRequest request) {
        return ApiResponse.ok(consultations.create(CurrentUser.get().id(),request==null?null:request.title()));
    }
    @GetMapping public ApiResponse<PageResult<SessionView>> list(@RequestParam(defaultValue="1") @Min(1) long page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) long pageSize) {
        return ApiResponse.ok(consultations.list(CurrentUser.get().id(),page,pageSize));
    }
    @GetMapping("/{id}") public ApiResponse<SessionView> detail(@PathVariable @Positive Long id) {
        return ApiResponse.ok(consultations.get(CurrentUser.get().id(),id));
    }
    @GetMapping("/{id}/messages") public ApiResponse<List<MessageView>> messages(@PathVariable @Positive Long id) {
        return ApiResponse.ok(consultations.messages(CurrentUser.get().id(),id));
    }
    @DeleteMapping("/{id}") public ApiResponse<Map<String,Boolean>> delete(@PathVariable @Positive Long id) {
        consultations.delete(CurrentUser.get().id(),id);
        return ApiResponse.ok(Map.of("deleted",true));
    }
    public record SummaryRequest(@NotNull @Valid ConfirmedSummary summary, @Min(0) int expectedSummaryVersion, boolean confirm) {}
    @PutMapping("/{id}/summary") public ApiResponse<SessionView> summary(@PathVariable @Positive Long id, @Valid @RequestBody SummaryRequest request) {
        return ApiResponse.ok(consultations.updateSummary(CurrentUser.get().id(), id, request.expectedSummaryVersion(), request.summary(), request.confirm()));
    }
    @PostMapping(value="/{id}/messages",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter send(@PathVariable @Positive Long id,@Valid @RequestBody SendMessageRequest request,HttpServletResponse response) {
        var emitter=streams.open(CurrentUser.get().id(),id,request,MDC.get("traceId"));
        response.setHeader("Cache-Control","no-cache");response.setHeader("X-Accel-Buffering","no");
        return emitter;
    }
    public record CancelRequest(@NotBlank @Size(max=64) String operationId) {}
    @PostMapping("/{id}/cancel") public ApiResponse<Map<String,Object>> cancel(@PathVariable @Positive Long id,
            @Valid @RequestBody CancelRequest request) {
        return ApiResponse.ok(Map.of("cancelled",streams.cancel(CurrentUser.get().id(),id,request.operationId())));
    }
}
