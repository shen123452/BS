package com.glucose;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
@ConfigurationPropertiesScan("com.glucose.config")
@MapperScan("com.glucose.**.mapper")
public class GlucoseApplication {

    public static void main(String[] args) {
        SpringApplication.run(GlucoseApplication.class, args);
    }
}
