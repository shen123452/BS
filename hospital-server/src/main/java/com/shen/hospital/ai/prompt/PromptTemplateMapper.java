package com.shen.hospital.ai.prompt;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Select;

public interface PromptTemplateMapper extends BaseMapper<PromptTemplate> {
    @Select("SELECT * FROM prompt_template WHERE code=#{code}")
    PromptTemplate byCode(String code);

    @Select("SELECT * FROM prompt_template ORDER BY id")
    List<PromptTemplate> all();
}
