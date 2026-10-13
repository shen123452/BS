package com.shen.hospital.common;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

@RestController
public class HealthController {
    private final DataSource mysql;
    private final DataSource vector;

    public HealthController(@Qualifier("mysqlDataSource") DataSource mysql, @Qualifier("vectorDataSource") DataSource vector) {
        this.mysql = mysql;
        this.vector = vector;
    }

    @GetMapping("/api/health")
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.ok(Map.of("status", "UP"));
    }

    @GetMapping("/api/admin/health")
    public ApiResponse<Map<String, String>> databases() {
        var result = new LinkedHashMap<String, String>();
        result.put("mysql", check(mysql, "SELECT 1"));
        result.put("vector", check(vector, "SELECT extversion FROM pg_extension WHERE extname='vector'"));
        return ApiResponse.ok(result);
    }

    private String check(DataSource ds, String sql) {
        try (var connection = ds.getConnection(); var statement = connection.createStatement()) {
            statement.setQueryTimeout(3);
            try (var rows = statement.executeQuery(sql)) {
                return rows.next() ? "UP" : "DOWN";
            }
        } catch (Exception ex) {
            return "DOWN";
        }
    }
}
