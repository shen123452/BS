package com.shen.hospital.ai.config;

import com.shen.hospital.ai.ModelFactory;
import com.shen.hospital.auth.security.CurrentUser;
import com.shen.hospital.common.ApiResponse;
import com.shen.hospital.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.*;

import com.shen.hospital.ai.config.AiModelConfigService.RevisionMeta;

/** 管理端模型配置：聊天/向量分离的修订保存、能力测试与激活；密钥不回传。 */
@RestController
@RequestMapping("/api/admin/ai-model-configs")
public class AdminAiModelController {
    private final AiModelConfigService service;
    private final ModelFactory models;

    public AdminAiModelController(AiModelConfigService service, ModelFactory models) {
        this.service = service; this.models = models;
    }

    public record ConfigRow(String id, String code, String name, String modelType, String status,
                            String currentRevision, RevisionMeta current, List<RevisionMeta> revisions) {}
    public record Overview(String effectiveSource, String effectiveBaseUrl, String effectiveChatModel,
                           String effectiveEmbeddingModel, int effectiveEmbeddingDimension, List<ConfigRow> configs) {}

    @GetMapping
    public ApiResponse<Overview> overview() {
        var effective = models.config();
        var rows = service.list().stream().map(config -> {
            var history = service.revisionsOf(config.getId()).stream().map(this::meta).toList();
            RevisionMeta current = null;
            if (config.getCurrentRevisionId() != null) {
                current = history.stream().filter(r -> String.valueOf(r.id()).equals(config.getCurrentRevisionId().toString()))
                        .findFirst().orElse(null);
            }
            return new ConfigRow(config.getId().toString(), config.getCode(), config.getName(), config.getModelType(),
                    config.getStatus(), config.getCurrentRevisionId() == null ? null : config.getCurrentRevisionId().toString(),
                    current, history);
        }).toList();
        return ApiResponse.ok(new Overview(effective.source(), effective.baseUrl(), effective.chatModel(),
                effective.embeddingModel(), effective.embeddingDimension(), rows));
    }

    public record CreateRequest(@NotBlank @Pattern(regexp = "CHAT|EMBEDDING") String modelType,
                                @NotBlank @Size(max = 100) String name,
                                @NotBlank @Size(max = 500) String baseUrl,
                                @NotBlank @Size(max = 100) String modelName,
                                @Size(max = 512) String apiKey,
                                @Min(1000) @Max(120000) Integer timeoutMs,
                                @DecimalMin("0") @DecimalMax("2") Double temperature,
                                @Min(64) @Max(8192) Integer maxOutputTokens,
                                @Min(1) @Max(16000) Integer embeddingDimension) {}
    @PostMapping
    public ApiResponse<ConfigRow> create(@Valid @RequestBody CreateRequest request) {
        var config = service.create(request.modelType(), toSave(request), CurrentUser.get().id());
        return ApiResponse.ok(row(config));
    }

    public record UpdateRequest(@NotBlank @Size(max = 500) String baseUrl,
                                @NotBlank @Size(max = 100) String modelName,
                                @Size(max = 512) String apiKey,
                                @Min(1000) @Max(120000) Integer timeoutMs,
                                @DecimalMin("0") @DecimalMax("2") Double temperature,
                                @Min(64) @Max(8192) Integer maxOutputTokens,
                                @Min(1) @Max(16000) Integer embeddingDimension) {}
    /** 修改生成新修订；apiKey 留空沿用最近一次修订的密钥。 */
    @PatchMapping("/{id}")
    public ApiResponse<RevisionMeta> update(@PathVariable @Positive Long id, @Valid @RequestBody UpdateRequest request) {
        return ApiResponse.ok(service.newRevision(id, toSave(request), CurrentUser.get().id()));
    }

    /** 对修订做真实供应商能力测试（会产生真实调用）；未指定修订时测试最新修订。 */
    @PostMapping("/{id}/test")
    public ApiResponse<RevisionMeta> test(@PathVariable @Positive Long id,
                                          @RequestBody(required = false) TestRequest request) {
        return ApiResponse.ok(service.test(id, request == null ? null : request.revisionId(), MDC.get("traceId")));
    }

    public record TestRequest(Long revisionId) {}

    public record ActivateRequest(@NotNull @Positive Long revisionId) {}
    @PostMapping("/{id}/activate")
    public ApiResponse<ConfigRow> activate(@PathVariable @Positive Long id, @Valid @RequestBody ActivateRequest request) {
        return ApiResponse.ok(row(service.activate(id, request.revisionId())));
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<ConfigRow> disable(@PathVariable @Positive Long id) {
        return ApiResponse.ok(row(service.disable(id)));
    }

    private AiModelConfigService.SaveRequest toSave(CreateRequest request) {
        return new AiModelConfigService.SaveRequest(request.name(), request.baseUrl(), request.modelName(),
                request.apiKey(), request.timeoutMs(),
                request.temperature() == null ? null : java.math.BigDecimal.valueOf(request.temperature()),
                request.maxOutputTokens(), request.embeddingDimension());
    }

    private AiModelConfigService.SaveRequest toSave(UpdateRequest request) {
        return new AiModelConfigService.SaveRequest(null, request.baseUrl(), request.modelName(),
                request.apiKey(), request.timeoutMs(),
                request.temperature() == null ? null : java.math.BigDecimal.valueOf(request.temperature()),
                request.maxOutputTokens(), request.embeddingDimension());
    }

    private AiModelConfigService.RevisionMeta meta(AiModelConfigRevision revision) {
        // 控制器层统一脱敏：测试说明不包含密钥，接口从不返回 api_key。
        return new AiModelConfigService.RevisionMeta(revision.getId(), revision.getRevision(), revision.getBaseUrl(),
                revision.getModelName(), revision.getApiKey() != null && !revision.getApiKey().isBlank(),
                revision.getTemperature(), revision.getMaxOutputTokens(), revision.getTimeoutMs(),
                revision.getEmbeddingDimension(), revision.getTestStatus(), revision.getTestedAt(),
                revision.getTestDetail(), revision.getCreatedAt());
    }

    private ConfigRow row(AiModelConfig config) {
        var history = service.revisionsOf(config.getId()).stream().map(this::meta).toList();
        RevisionMeta current = config.getCurrentRevisionId() == null ? null
                : history.stream().filter(r -> r.id().toString().equals(config.getCurrentRevisionId().toString()))
                        .findFirst().orElse(null);
        return new ConfigRow(config.getId().toString(), config.getCode(), config.getName(), config.getModelType(),
                config.getStatus(), config.getCurrentRevisionId() == null ? null : config.getCurrentRevisionId().toString(),
                current, history);
    }
}
