package com.shen.hospital.ai.agent;

import com.shen.hospital.ai.tool.MedicalToolService;
import com.shen.hospital.common.*;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminAgentController {
    private final MedicalToolService tools;
    private final TriageAgentService agent;
    public AdminAgentController(MedicalToolService tools,TriageAgentService agent) {this.tools=tools;this.agent=agent;}
    @GetMapping("/agent-tools")
    public ApiResponse<List<Map<String,Object>>> tools(){
        return ApiResponse.ok(tools.specifications().stream().map(t->Map.<String,Object>of(
                "name",t.name(),"description",t.description(),"readOnly",true)).toList());
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
}
