package com.shen.hospital.masterdata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shen.hospital.masterdata.domain.PatientProfile;
import org.apache.ibatis.annotations.*;

public interface PatientProfileMapper extends BaseMapper<PatientProfile> {
    @Select("SELECT id FROM patient_profile WHERE user_id=#{userId}")
    Long findIdByUserId(Long userId);

    @Select("SELECT * FROM patient_profile WHERE user_id=#{userId} FOR UPDATE")
    PatientProfile lockByUserId(Long userId);

    @Select("SELECT * FROM patient_profile WHERE id=#{id} FOR UPDATE")
    PatientProfile lockById(Long id);

    @Update("""
            UPDATE patient_profile SET name=#{p.name}, gender=#{p.gender}, age=#{p.age},
              chronic_status=#{p.chronicStatus}, chronic_history=#{p.chronicHistory},
              allergy_status=#{p.allergyStatus}, allergy_history=#{p.allergyHistory}
            WHERE user_id=#{userId}
            """)
    int updateOwn(@Param("userId") Long userId, @Param("p") PatientProfile profile);
}
