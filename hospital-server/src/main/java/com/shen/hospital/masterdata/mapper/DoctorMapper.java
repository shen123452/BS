package com.shen.hospital.masterdata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shen.hospital.masterdata.domain.Doctor;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

public interface DoctorMapper extends BaseMapper<Doctor> {
    @Select("SELECT id FROM doctor WHERE user_id=#{userId}")
    Long findIdByUserId(Long userId);

    @Select("SELECT * FROM doctor WHERE id=#{id} FOR UPDATE")
    Doctor lockById(Long id);

    @Select("SELECT id FROM doctor_schedule WHERE doctor_id=#{id} AND visit_date>=#{today} AND status='OPEN' LIMIT 1 FOR UPDATE")
    Long futureOpenSchedule(@Param("id") Long id, @Param("today") LocalDate today);
}
