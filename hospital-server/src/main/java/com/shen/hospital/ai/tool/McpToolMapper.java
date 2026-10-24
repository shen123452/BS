package com.shen.hospital.ai.tool;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface McpToolMapper extends BaseMapper<McpTool> {
    @Select("SELECT * FROM mcp_tool WHERE code=#{code}")
    McpTool byCode(String code);

    @Select("SELECT * FROM mcp_tool WHERE status='ENABLED' ORDER BY sort_no,id")
    List<McpTool> enabled();

    @Select("SELECT * FROM mcp_tool ORDER BY sort_no,id")
    List<McpTool> all();

    @Update("UPDATE mcp_tool SET status=#{status},revision=revision+1 WHERE id=#{id} AND status<>#{status}")
    int flipStatus(@Param("id") Long id, @Param("status") String status);
}
