package com.shen.hospital.masterdata.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shen.hospital.auth.security.CurrentUser;
import com.shen.hospital.common.*;
import com.shen.hospital.masterdata.domain.*;
import com.shen.hospital.masterdata.mapper.*;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 管理端症状标签与科室映射维护：停用标签不关闭代码中的强制安全规则。 */
@RestController
@RequestMapping("/api/admin")
public class AdminSymptomController {
    private final SymptomTagMapper tags;
    private final SymptomDepartmentRuleMapper rules;
    private final DepartmentMapper departments;

    public AdminSymptomController(SymptomTagMapper tags, SymptomDepartmentRuleMapper rules, DepartmentMapper departments) {
        this.tags = tags; this.rules = rules; this.departments = departments;
    }

    public record TagRow(String id, String code, String name, Object aliases, String description, boolean redFlag,
                         String confirmationQuestion, String status, Integer ruleRevision, int ruleCount,
                         List<RuleRow> rules, java.time.LocalDateTime updatedAt) {}
    public record RuleRow(String id, String departmentId, String departmentName, String weight, String status) {}

    @GetMapping("/symptom-tags")
    public ApiResponse<List<TagRow>> list() {
        return ApiResponse.ok(tags.selectList(new LambdaQueryWrapper<SymptomTag>().orderByAsc(SymptomTag::getId))
                .stream().map(this::row).toList());
    }

    public record TagRequest(@NotBlank @Size(max=64) String code, @NotBlank @Size(max=100) String name,
                             String aliases, @Size(max=500) String description, Boolean redFlag,
                             @Size(max=500) String confirmationQuestion) {}
    @PostMapping("/symptom-tags")
    public ApiResponse<TagRow> create(@org.springframework.validation.annotation.Validated TagRequest request) {
        if (tags.byCode(request.code().strip()) != null) throw BusinessException.conflict("SYMPTOM_CODE_EXISTS", "症状编码已存在");
        validateAliases(request.aliases());
        var tag = new SymptomTag();
        tag.setCode(request.code().strip()); tag.setName(request.name().strip());
        tag.setAliases(request.aliases()); tag.setDescription(request.description());
        tag.setIsRedFlag(Boolean.TRUE.equals(request.redFlag) ? 1 : 0);
        tag.setConfirmationQuestion(request.confirmationQuestion());
        tag.setStatus("ENABLED"); tag.setRuleRevision(1);
        tags.insert(tag);
        return ApiResponse.ok(row(tags.byCode(tag.getCode())));
    }

    public record TagUpdate(@Size(max=100) String name, String aliases, @Size(max=500) String description,
                            @Size(max=500) String confirmationQuestion) {}
    @PatchMapping("/symptom-tags/{id}")
    public ApiResponse<TagRow> update(@PathVariable @Positive Long id,
                                      @org.springframework.validation.annotation.Validated TagUpdate request) {
        var tag = owned(id);
        var update = new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SymptomTag>()
                .eq(SymptomTag::getId, id).set(SymptomTag::getRuleRevision, tag.getRuleRevision() + 1);
        if (request.name() != null) update.set(SymptomTag::getName, request.name().strip());
        if (request.aliases() != null) { validateAliases(request.aliases()); update.set(SymptomTag::getAliases, request.aliases()); }
        if (request.description() != null) update.set(SymptomTag::getDescription, request.description());
        if (request.confirmationQuestion() != null) update.set(SymptomTag::getConfirmationQuestion, request.confirmationQuestion());
        tags.update(null, update);
        return ApiResponse.ok(row(tags.selectById(id)));
    }

    public record StatusRequest(@NotBlank @Pattern(regexp="ENABLED|DISABLED") String status) {}
    @PostMapping("/symptom-tags/{id}/status")
    public ApiResponse<TagRow> flip(@PathVariable @Positive Long id,
                                    @org.springframework.validation.annotation.Validated StatusRequest request) {
        owned(id);
        if (tags.flipStatus(id, request.status()) != 1)
            throw BusinessException.conflict("SYMPTOM_STATUS_UNCHANGED", "标签状态未变化，请刷新后重试");
        return ApiResponse.ok(row(tags.selectById(id)));
    }

    public record RuleRequest(@NotNull @Positive Long departmentId, @NotNull @DecimalMin("0.001") @DecimalMax("9.999") java.math.BigDecimal weight) {}
    @PostMapping("/symptom-tags/{id}/rules")
    public ApiResponse<TagRow> addRule(@PathVariable @Positive Long id,
                                       @org.springframework.validation.annotation.Validated @NotNull RuleRequest request) {
        var tag = owned(id);
        var department = departments.selectById(request.departmentId());
        if (department == null) throw BusinessException.missing("科室");
        var existing = rules.selectOne(new LambdaQueryWrapper<SymptomDepartmentRule>()
                .eq(SymptomDepartmentRule::getSymptomTagId, id)
                .eq(SymptomDepartmentRule::getDepartmentId, request.departmentId()));
        if (existing != null) throw BusinessException.conflict("SYMPTOM_RULE_EXISTS", "该科室映射已存在，请先删除后新增");
        var rule = new SymptomDepartmentRule();
        rule.setSymptomTagId(id); rule.setDepartmentId(request.departmentId());
        rule.setWeight(request.weight()); rule.setStatus("ENABLED");
        rules.insert(rule);
        tags.update(new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SymptomTag>()
                .eq(SymptomTag::getId, id).set(SymptomTag::getRuleRevision, tag.getRuleRevision() + 1));
        return ApiResponse.ok(row(tags.selectById(id)));
    }

    @DeleteMapping("/symptom-rules/{id}")
    public ApiResponse<TagRow> removeRule(@PathVariable @Positive Long id) {
        var rule = rules.selectById(id);
        if (rule == null) throw BusinessException.missing("科室映射");
        rules.deleteById(id);
        var tag = tags.selectById(rule.getSymptomTagId());
        if (tag != null) tags.update(new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SymptomTag>()
                .eq(SymptomTag::getId, tag.getId()).set(SymptomTag::getRuleRevision, tag.getRuleRevision() + 1));
        return ApiResponse.ok(tag == null ? null : row(tags.selectById(tag.getId())));
    }

    private SymptomTag owned(Long id) {
        var tag = tags.selectById(id);
        if (tag == null) throw BusinessException.missing("症状标签");
        return tag;
    }

    private TagRow row(SymptomTag tag) {
        var ruleRows = rules.enabledRulesWithDepartment(tag.getId()).stream()
                .map(r -> new RuleRow(r.getId().toString(), r.getDepartmentId().toString(), r.getDepartmentName(),
                        r.getWeight().toPlainString(), "ENABLED")).toList();
        Object aliases = null;
        if (tag.getAliases() != null) {
            try { aliases = new com.fasterxml.jackson.databind.ObjectMapper().readTree(tag.getAliases()); }
            catch (Exception ignored) { aliases = tag.getAliases(); }
        }
        return new TagRow(tag.getId().toString(), tag.getCode(), tag.getName(), aliases, tag.getDescription(),
                tag.getIsRedFlag() != null && tag.getIsRedFlag() == 1, tag.getConfirmationQuestion(),
                tag.getStatus(), tag.getRuleRevision(), ruleRows.size(), ruleRows, tag.getUpdatedAt());
    }

    private void validateAliases(String aliases) {
        if (aliases == null || aliases.isBlank()) return;
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(aliases);
            if (!node.isArray() || node.size() > 20) throw new IllegalArgumentException("aliases");
            for (var item : node) if (!item.isTextual() || item.asText().isBlank() || item.asText().length() > 100)
                throw new IllegalArgumentException("aliases");
        } catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw BusinessException.invalid("aliases 必须是最多20个非空字符串的JSON数组"); }
    }
}
