package com.glucose.knowledge.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.glucose.common.api.PageResult;
import com.glucose.common.api.Result;
import com.glucose.knowledge.entity.KbChunk;
import com.glucose.knowledge.entity.KbDocument;
import com.glucose.knowledge.mapper.KbChunkMapper;
import com.glucose.knowledge.mapper.KbDocumentMapper;
import com.glucose.knowledge.service.KbService;
import com.glucose.security.LoginContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 知识库管理(仅管理员)。
 */
@RestController
@RequestMapping("/api/admin/kb")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class KbAdminController {

    private final KbService kbService;
    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;

    @GetMapping("/documents")
    public Result<PageResult<KbService.DocumentVO>> list(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String indexStatus) {
        var query = Wrappers.<KbDocument>lambdaQuery()
                .orderByDesc(KbDocument::getUpdatedAt);
        if (keyword != null && !keyword.isBlank()) {
            query.and(q -> q.like(KbDocument::getTitle, keyword).or().like(KbDocument::getCategory, keyword));
        }
        if (indexStatus != null && !indexStatus.isBlank()) {
            query.eq(KbDocument::getIndexStatus, indexStatus);
        }
        Page<KbDocument> page = documentMapper.selectPage(new Page<>(pageNum, Math.min(pageSize, 100)), query);
        return Result.ok(PageResult.of(page.getTotal(), page.getCurrent(),
                page.getRecords().stream().map(kbService::toVO).toList()));
    }

    @PostMapping("/documents")
    public Result<KbService.DocumentVO> create(@RequestBody KbService.UpsertReq req) {
        return Result.ok(kbService.create(req));
    }

    @GetMapping("/documents/{id}")
    public Result<Map<String, Object>> detail(@PathVariable long id) {
        KbDocument doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new com.glucose.common.exception.BusinessException(com.glucose.common.api.ErrorCode.NOT_FOUND);
        }
        KbService.DocumentVO vo = kbService.toVO(doc);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", vo.id());
        out.put("title", vo.title());
        out.put("category", vo.category());
        out.put("sourceTitle", vo.sourceTitle());
        out.put("sourceUrl", vo.sourceUrl());
        out.put("sourceVersion", vo.sourceVersion());
        out.put("publishedOn", vo.publishedOn());
        out.put("documentVersion", vo.documentVersion());
        out.put("contentHash", vo.contentHash());
        out.put("status", vo.status());
        out.put("indexStatus", vo.indexStatus());
        out.put("activeIndexVersion", vo.activeIndexVersion() == null ? "" : vo.activeIndexVersion());
        out.put("chunkCount", vo.chunkCount());
        out.put("content", doc.getContent());
        return Result.ok(out);
    }

    @PutMapping("/documents/{id}")
    public Result<KbService.DocumentVO> update(@PathVariable long id, @RequestBody KbService.UpsertReq req) {
        return Result.ok(kbService.update(id, req));
    }

    @DeleteMapping("/documents/{id}")
    public Result<Void> delete(@PathVariable long id) {
        kbService.delete(id);
        return Result.ok();
    }

    @PutMapping("/documents/{id}/status")
    public Result<Void> toggle(@PathVariable long id, @RequestBody Map<String, Integer> body) {
        Integer status = body.get("status");
        if (status == null || (status != 0 && status != 1)) {
            throw new com.glucose.common.exception.BusinessException(
                    com.glucose.common.api.ErrorCode.PARAM_ERROR, "status 必须为 0 或 1");
        }
        kbService.toggleStatus(id, status);
        return Result.ok();
    }

    @PostMapping("/documents/{id}/reindex")
    public Result<KbService.DocumentVO> reindex(@PathVariable long id) {
        kbService.reindex(id);
        KbDocument doc = documentMapper.selectById(id);
        return Result.ok(kbService.toVO(doc));
    }

    @GetMapping("/documents/{id}/chunks")
    public Result<List<Map<String, Object>>> chunks(@PathVariable long id) {
        List<KbChunk> chunks = kbService.chunks(id);
        return Result.ok(chunks.stream().map(c -> Map.<String, Object>of(
                "chunkNo", c.getChunkNo(),
                "content", c.getContent(),
                "embeddingModel", c.getEmbeddingModel(),
                "dim", c.getDim(),
                "indexVersion", c.getIndexVersion())).toList());
    }
}
