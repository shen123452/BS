package com.shen.hospital.masterdata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shen.hospital.masterdata.domain.Department;
import org.apache.ibatis.annotations.Select;

public interface DepartmentMapper extends BaseMapper<Department> {
    @Select("""
            SELECT (SELECT COUNT(*) FROM doctor WHERE department_id=#{id} AND status='ENABLED')
                 + (SELECT COUNT(*) FROM doctor_schedule WHERE department_id=#{id}
                     AND visit_date >= CURRENT_DATE() AND status='OPEN')
            """)
    long countActiveDependencies(Long id);

    @Select("SELECT * FROM department WHERE id=#{id} FOR UPDATE")
    Department lockById(Long id);
}
