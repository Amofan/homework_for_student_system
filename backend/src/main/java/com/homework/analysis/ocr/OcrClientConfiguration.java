package com.homework.analysis.ocr;

import com.homework.analysis.shared.error.DomainException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;

@Configuration
class OcrClientConfiguration {

    @Bean
    @ConditionalOnProperty(name = "app.ocr.enabled", havingValue = "true")
    OcrProvider httpOcrProvider(OcrProperties properties, ObjectMapper objectMapper) {
        // 与模型客户端同样固定 HTTP/1.1：请求体是几 MB 的 base64 JSON，HTTP/2 下
        // 流式序列化容易出现 content-length 与实际写入字节不一致而被对端重置。
        HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(properties.timeout())
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.timeout());
        RestClient client = RestClient.builder()
            .baseUrl(properties.baseUrl())
            .requestFactory(requestFactory)
            .build();
        return new HttpOcrProvider(client, objectMapper, properties);
    }

    /**
     * 未启用时的兜底实现。
     *
     * <p>抛"服务不可用"而不是"未实现"：任务编排会据此走有限重试，等 OCR 服务起来后自动补上。
     * 直接判失败会让一批作业在服务尚未启动时被永久标记为失败，而那时教师还没做错任何事。
     */
    @Bean
    @ConditionalOnMissingBean(OcrProvider.class)
    OcrProvider disabledOcrProvider() {
        return request -> {
            throw new DomainException("OCR_UNAVAILABLE",
                "尚未启用 OCR 服务；请配置 OCR_ENABLED=true 及服务地址", HttpStatus.SERVICE_UNAVAILABLE);
        };
    }
}
