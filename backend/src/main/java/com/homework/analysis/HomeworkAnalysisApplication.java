package com.homework.analysis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.homework.analysis.grading.ai.ModelProperties;

@SpringBootApplication
@EnableConfigurationProperties(ModelProperties.class)
public class HomeworkAnalysisApplication {
    public static void main(String[] args) {
        SpringApplication.run(HomeworkAnalysisApplication.class, args);
    }
}
