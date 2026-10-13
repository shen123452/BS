package com.shen.hospital.masterdata.mapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

public interface StatisticsMapper {
    @Select("SELECT status,COUNT(*) AS count FROM registration WHERE visit_date BETWEEN #{start} AND #{end} GROUP BY status ORDER BY status")
    List<Map<String,Object>> registrations(@Param("start") LocalDate start,@Param("end") LocalDate end);
    @Select("SELECT status,COUNT(*) AS count FROM agent_run WHERE created_at>=#{start} AND created_at<DATE_ADD(#{end},INTERVAL 1 DAY) GROUP BY status ORDER BY status")
    List<Map<String,Object>> agents(@Param("start") LocalDate start,@Param("end") LocalDate end);
    @Select("SELECT d.id AS departmentId,d.name AS departmentName,COUNT(*) AS count FROM registration r JOIN department d ON d.id=r.department_id WHERE r.visit_date BETWEEN #{start} AND #{end} GROUP BY d.id,d.name ORDER BY count DESC,d.id")
    List<Map<String,Object>> departments(@Param("start") LocalDate start,@Param("end") LocalDate end);
    @Select("SELECT visit_date AS date,COUNT(*) AS count FROM registration WHERE visit_date BETWEEN #{start} AND #{end} GROUP BY visit_date ORDER BY visit_date")
    List<Map<String,Object>> daily(@Param("start") LocalDate start,@Param("end") LocalDate end);
}
