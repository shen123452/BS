package com.shen.hospital.ai.prompt;

import com.shen.hospital.auth.security.CurrentUser;
import com.shen.hospital.common.*;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** 管理端 Prompt 模板：草稿编辑、发布、回滚与修订历史。 */
@RestController
@RequestMapping("/api/admin/prompt-templates")
public class AdminPromptController {
    private final PromptService prompts;

    public AdminPromptController(PromptService prompts) { this.prompts = prompts; }

    public record Summary(String id, String code, String name, String purpose, String status,
                          String currentRevision, java.time.LocalDateTime updatedAt) {}
    @GetMapping
    public ApiResponse<List<Summary>> list() {
        return ApiResponse.ok(prompts.list().stream().map(t -> new Summary(t.getId().toString(), t.getCode(),
                t.getName(), t.getPurpose(), t.getStatus(),
                t.getCurrentRevisionId() == null ? null : String.valueOf(t.getCurrentRevisionId()), t.getUpdatedAt())).toList());
    }

    @GetMapping("/{code}")
    public ApiResponse<PromptService.TemplateView> detail(@PathVariable String code) {
        return ApiResponse.ok(prompts.detail(code));
    }

    public record DraftRequest(@NotNull @Size(max=12000) String content) {}
    @PatchMapping("/{code}/draft")
    public ApiResponse<Void> saveDraft(@PathVariable String code,
                                       @org.springframework.validation.annotation.Validated @NotNull DraftRequest request) {
        prompts.saveDraft(code, request.content(), CurrentUser.get().id());
        return ApiResponse.ok(null);
    }

    public record PublishRequest(@Size(max=500) String changeNote) {}
    @PostMapping("/{code}/publish")
    public ApiResponse<PromptService.RevisionView> publish(@PathVariable String code,
            @org.springframework.validation.annotation.Validated PublishRequest request) {
        return ApiResponse.ok(prompts.publish(code, request.changeNote(), CurrentUser.get().id()));
    }

    public record RollbackRequest(@NotNull @Min(1) Integer targetRevision) {}
    @PostMapping("/{code}/rollback")
    public ApiResponse<Void> rollback(@PathVariable String code,
            @org.springframework.validation.annotation.Validated @NotNull RollbackRequest request) {
        prompts.rollback(code, request.targetRevision());
        return ApiResponse.ok(null);
    }
}
