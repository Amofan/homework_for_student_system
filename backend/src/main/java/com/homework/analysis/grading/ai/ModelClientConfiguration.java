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
        // 显式固定 HTTP/1.1：JDK HttpClient 默认协商 HTTP/2，而 JdkClientHttpRequestFactory
        // 在 HTTP/2 下发送 Jackson 流式序列化的请求体（如 Map）时，content-length 与实际
        // DATA 帧字节数不一致，对端按协议违规回 RST_STREAM，表现为 "EOF reached while reading"。
        // 模型调用是低频且单次请求体较大的场景，HTTP/2 多路复用无收益，固定 1.1 可消除该失败模式。
        HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
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
