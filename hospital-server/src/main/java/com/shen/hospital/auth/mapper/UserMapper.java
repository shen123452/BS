package com.shen.hospital.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shen.hospital.auth.domain.SysUser;
import org.apache.ibatis.annotations.Select;

public interface UserMapper extends BaseMapper<SysUser> {
    @Select("SELECT * FROM sys_user WHERE id=#{id} FOR UPDATE")
    SysUser lockById(Long id);
}
