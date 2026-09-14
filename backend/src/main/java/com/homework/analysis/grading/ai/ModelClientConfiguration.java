package com.homework.analysis.grading.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
class ModelClientConfiguration {
    @Bean
    @ConditionalOnProperty(name = "app.model.enabled", havingValue = "true")
    RestClient modelRestClient(ModelProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(properties.timeoutSeconds()))
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(properties.timeoutSeconds()));
        return RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
    }

    @Bean
    @ConditionalOnMissingBean(AiModelClient.class)
    AiModelClient disabledModelClient() {
        return request -> {
            throw new IllegalStateException("尚未启用大模型；请配置 MODEL_ENABLED=true 及模型连接参数");
        };
    }
}
