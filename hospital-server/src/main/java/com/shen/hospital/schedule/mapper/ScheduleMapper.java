package com.shen.hospital.schedule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shen.hospital.schedule.domain.DoctorSchedule;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.apache.ibatis.annotations.*;

public interface ScheduleMapper extends BaseMapper<DoctorSchedule> {
    String SELECT_ROWS = """
            SELECT s.*, d.name AS doctor_name, dep.name AS department_name,
              (s.status='OPEN' AND d.status='ENABLED' AND dep.status='ENABLED' AND u.status='ACTIVE'
               AND d.department_id=s.department_id AND s.remaining_count>0 AND s.booking_deadline>#{now}
               AND s.visit_date BETWEEN DATE(#{now}) AND DATE_ADD(DATE(#{now}), INTERVAL 6 DAY)) AS bookable
            FROM doctor_schedule s JOIN doctor d ON d.id=s.doctor_id
            JOIN department dep ON dep.id=s.department_id JOIN sys_user u ON u.id=d.user_id
            """;
    String VISIBLE = """
            s.status='OPEN' AND d.status='ENABLED' AND dep.status='ENABLED' AND u.status='ACTIVE'
            AND d.department_id=s.department_id AND s.visit_date BETWEEN #{start} AND #{end}
            """;

    @Select("SELECT * FROM doctor_schedule WHERE id=#{id} FOR UPDATE")
    DoctorSchedule lockById(Long id);

    @Select(SELECT_ROWS + " WHERE s.id=#{id}")
    DoctorSchedule viewById(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Select(SELECT_ROWS + " WHERE s.id=#{id} AND " + VISIBLE)
    DoctorSchedule visibleById(@Param("id") Long id, @Param("start") LocalDate start,
                               @Param("end") LocalDate end, @Param("now") LocalDateTime now);

    @Select("<script>" + SELECT_ROWS + " WHERE " + VISIBLE + """
            AND s.remaining_count&gt;0 AND s.booking_deadline&gt;#{now}
            <if test='departmentId != null'>AND s.department_id=#{departmentId}</if>
            <if test='doctorId != null'>AND s.doctor_id=#{doctorId}</if>
            ORDER BY s.visit_date, s.time_slot, s.doctor_id, s.id
            </script>
            """)
    IPage<DoctorSchedule> available(Page<DoctorSchedule> page, @Param("departmentId") Long departmentId,
                                    @Param("doctorId") Long doctorId, @Param("start") LocalDate start,
                                    @Param("end") LocalDate end, @Param("now") LocalDateTime now);

    @Select("<script>" + SELECT_ROWS + """
            WHERE s.visit_date BETWEEN #{start} AND #{end}
            <if test='departmentId != null'>AND s.department_id=#{departmentId}</if>
            <if test='doctorId != null'>AND s.doctor_id=#{doctorId}</if>
            <if test='status != null'>AND s.status=#{status}</if>
            ORDER BY s.visit_date, s.time_slot, s.doctor_id, s.id
            </script>
            """)
    IPage<DoctorSchedule> managed(Page<DoctorSchedule> page, @Param("departmentId") Long departmentId,
                                  @Param("doctorId") Long doctorId, @Param("status") String status,
                                  @Param("start") LocalDate start, @Param("end") LocalDate end, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE doctor_schedule SET remaining_count=remaining_count-1
            WHERE id=#{id} AND status='OPEN' AND remaining_count>0 AND booking_deadline>#{now}
              AND visit_date BETWEEN DATE(#{now}) AND DATE_ADD(DATE(#{now}), INTERVAL 6 DAY)
            """)
    int takeOne(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Update("UPDATE doctor_schedule SET remaining_count=remaining_count+1 WHERE id=#{id} AND remaining_count<total_count")
    int returnOne(Long id);
}
