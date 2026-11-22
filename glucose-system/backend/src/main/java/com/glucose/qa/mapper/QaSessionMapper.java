package com.glucose.qa.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.glucose.qa.entity.QaSession;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

public interface QaSessionMapper extends BaseMapper<QaSession> {
    @Select("SELECT * FROM qa_session WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    QaSession lockById(@Param("id") long id);
}
