package com.shen.hospital.masterdata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shen.hospital.masterdata.domain.SymptomDepartmentRule;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SymptomDepartmentRuleMapper extends BaseMapper<SymptomDepartmentRule> {
    @Select("SELECT r.id,r.symptom_tag_id AS symptomTagId,r.department_id AS departmentId,r.weight,r.status,"
            + "d.name AS departmentName FROM symptom_department_rule r "
            + "JOIN department d ON d.id=r.department_id "
            + "WHERE r.symptom_tag_id=#{tagId} AND r.status='ENABLED' AND d.status='ENABLED' ORDER BY r.weight DESC,d.id")
    List<RuleView> enabledRulesWithDepartment(@Param("tagId") Long tagId);

    @Select("SELECT COUNT(*) FROM symptom_department_rule WHERE department_id=#{departmentId}")
    int countByDepartment(@Param("departmentId") Long departmentId);

    interface RuleView {
        Long getId(); Long getDepartmentId(); String getDepartmentName(); java.math.BigDecimal getWeight();
    }
}
