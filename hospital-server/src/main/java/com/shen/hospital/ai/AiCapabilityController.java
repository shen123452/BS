package com.shen.hospital.ai;

import com.shen.hospital.common.ApiResponse;

import java.util.Map;

import org.slf4j.MDC;
import org.springframework.web.bind.annotation.*;
import com.shen.hospital.auth.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

@RestController
@RequestMapping("/api/admin/ai")
public class AiCapabilityController {
    private final AiCapabilityService capabilities;
    private final AiApiKeyStore keys;

    public AiCapabilityController(AiCapabilityService capabilities, AiApiKeyStore keys) {
        this.capabilities = capabilities;
        this.keys = keys;
    }

    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> config() {
        return ApiResponse.ok(capabilities.config());
    }

    public record SaveApiKeyRequest(@NotBlank @Size(max=600) String apiKey) {
        @Override public String toString() { return "SaveApiKeyRequest[apiKey=[REDACTED]]"; }
    }

    @PutMapping("/api-key")
    public ApiResponse<Map<String, Object>> saveApiKey(@Valid @RequestBody SaveApiKeyRequest request) {
        keys.save(request.apiKey(), CurrentUser.get().id());
        return ApiResponse.ok(capabilities.config());
    }

    @PostMapping("/capabilities/test")
    public ApiResponse<AiCapabilityService.Report> probe() {
        return ApiResponse.ok(capabilities.probe(MDC.get("traceId")));
    }
}
