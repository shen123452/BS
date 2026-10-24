package com.shen.hospital.ai.agent;

import com.shen.hospital.ai.tool.ToolRegistry;
import com.shen.hospital.common.*;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminAgentController {
    private final ToolRegistry tools;
    private final TriageAgentService agent;
    public AdminAgentController(ToolRegistry tools,TriageAgentService agent) {this.tools=tools;this.agent=agent;}
    /** 下一次新运行可用的工具池，来自数据库启用状态。 */
    @GetMapping("/agent-tools")
    public ApiResponse<List<Map<String,Object>>> tools(){
        return ApiResponse.ok(tools.enabled().stream().map(t->Map.<String,Object>of(
                "name",t.getCode(),"description",t.getDescription(),"readOnly",true,
                "status",t.getStatus(),"revision",t.getRevision())).toList());
    }
    @GetMapping("/agent-runs")
    public ApiResponse<PageResult<TriageAgentService.RunView>> list(
            @RequestParam(required=false) @Pattern(regexp="QUEUED|RUNNING|SUCCEEDED|DEGRADED|FAILED|CANCELLED") String status,
            @RequestParam(defaultValue="1") @Min(1) long page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) long pageSize) {
        return ApiResponse.ok(agent.adminList(status,page,pageSize));
    }
    @GetMapping("/agent-runs/{id}")
    public ApiResponse<TriageAgentService.RunView> detail(@PathVariable @Positive Long id){
        return ApiResponse.ok(agent.adminDetail(id));
    }
    /** 运行回放：模型/Prompt/工具/知识快照与预算，一次执行可定位版本与失败来源。 */
    @GetMapping("/agent-runs/{id}/replay")
    public ApiResponse<TriageAgentService.AdminRunView> replay(@PathVariable @Positive Long id){
        return ApiResponse.ok(agent.adminReplay(id));
    }
}
