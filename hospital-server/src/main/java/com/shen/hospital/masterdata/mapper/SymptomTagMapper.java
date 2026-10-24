package com.shen.hospital.masterdata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shen.hospital.masterdata.domain.SymptomTag;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface SymptomTagMapper extends BaseMapper<SymptomTag> {
    @Select("SELECT * FROM symptom_tag WHERE code=#{code}")
    SymptomTag byCode(String code);

    @Select("SELECT * FROM symptom_tag WHERE status='ENABLED' ORDER BY id")
    List<SymptomTag> enabled();

    @Update("UPDATE symptom_tag SET status=#{status},rule_revision=rule_revision+1 WHERE id=#{id} AND status<>#{status}")
    int flipStatus(@Param("id") Long id, @Param("status") String status);
}
