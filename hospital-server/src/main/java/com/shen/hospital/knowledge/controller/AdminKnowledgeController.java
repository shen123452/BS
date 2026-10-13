package com.shen.hospital.knowledge.controller;

import com.shen.hospital.auth.security.CurrentUser;
import com.shen.hospital.common.*;
import com.shen.hospital.knowledge.dto.*;
import com.shen.hospital.knowledge.service.KnowledgeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.slf4j.MDC;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/knowledge")
public class AdminKnowledgeController {
    private final KnowledgeService knowledge;
    public AdminKnowledgeController(KnowledgeService knowledge) { this.knowledge=knowledge; }

    @PostMapping(value="/documents",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<KnowledgeService.UploadResult>> upload(@RequestPart MultipartFile file,
            @RequestParam @NotBlank @Size(max=200) String title,
            @RequestParam @NotBlank @Size(max=1000) String source,
            @RequestParam @NotBlank @Size(max=100) String version) {
        var result=knowledge.upload(CurrentUser.get().id(),file,title,source,version,MDC.get("traceId"));
        return ResponseEntity.status(result.duplicate()?200:202).body(ApiResponse.ok(result));
    }
    @GetMapping("/documents")
    public ApiResponse<PageResult<KnowledgeDocumentView>> list(@RequestParam(defaultValue="1") @Min(1) long page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) long pageSize) {
        return ApiResponse.ok(knowledge.list(page,pageSize));
    }
    @GetMapping("/documents/{id}")
    public ApiResponse<KnowledgeDocumentView> detail(@PathVariable @Positive Long id) { return ApiResponse.ok(knowledge.detail(id)); }
    @GetMapping("/documents/{id}/chunks")
    public ApiResponse<PageResult<KnowledgeChunkView>> chunks(@PathVariable @Positive Long id,
            @RequestParam(defaultValue="1") @Min(1) long page, @RequestParam(defaultValue="20") @Min(1) @Max(100) long pageSize) {
        return ApiResponse.ok(knowledge.chunkList(id,page,pageSize));
    }
    public record EnabledRequest(@NotNull Boolean enabled) {}
    @PatchMapping("/documents/{id}/enabled")
    public ApiResponse<KnowledgeDocumentView> enabled(@PathVariable @Positive Long id,@Valid @RequestBody EnabledRequest request) {
        return ApiResponse.ok(knowledge.enabled(id,request.enabled()));
    }
    @PostMapping("/documents/{id}/retry")
    public ResponseEntity<ApiResponse<KnowledgeDocumentView>> retry(@PathVariable @Positive Long id) {
        return ResponseEntity.accepted().body(ApiResponse.ok(knowledge.retry(id,MDC.get("traceId"))));
    }
    @GetMapping("/status")
    public ApiResponse<KnowledgeService.KnowledgeStatus> status() { return ApiResponse.ok(knowledge.status()); }
    public record SearchRequest(@NotBlank @Size(max=1000) String query,@Min(1) @Max(10) Integer topK) {}
    @PostMapping("/search")
    public ApiResponse<KnowledgeService.SearchResult> search(@Valid @RequestBody SearchRequest request) {
        return ApiResponse.ok(knowledge.search(request.query(),request.topK()==null?5:request.topK(),MDC.get("traceId")));
    }
}
