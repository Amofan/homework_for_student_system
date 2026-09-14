package com.homework.analysis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import com.homework.analysis.grading.ai.ModelProperties;

import java.time.Clock;

@SpringBootApplication
@EnableConfigurationProperties(ModelProperties.class)
public class HomeworkAnalysisApplication {
    public static void main(String[] args) {
        SpringApplication.run(HomeworkAnalysisApplication.class, args);
    }

    /** 统一的时钟来源，便于测试注入固定时钟验证重试退避时间。 */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
