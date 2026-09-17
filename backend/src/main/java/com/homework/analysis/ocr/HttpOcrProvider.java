package com.homework.analysis.ocr;

import com.homework.analysis.shared.error.DomainException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 通过内部 HTTP 调用 OCR 服务。
 *
 * <p>三件事按顺序做，顺序本身就是设计：先序列化并在本地量体积（超限就别发了），
 * 再发请求，最后校验响应契约。把体积检查放在发送之后等于把几百 MB 的请求推给对端再等超时。
 *
 * <p>日志里不出现页面图与识别文本：前者是学生答卷，后者是可读的答案内容。
 * 异常包装只保留异常类型与状态码，不拼接请求体与响应体。
 */
public final class HttpOcrProvider implements OcrProvider {

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final String analyzePath;
    private final String token;
    private final long maxRequestBytes;

    public HttpOcrProvider(RestClient client, ObjectMapper objectMapper, OcrProperties properties) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.analyzePath = properties.analyzePath();
        this.token = properties.token();
        this.maxRequestBytes = properties.maxRequestBytes();
    }

    @Override
    public OcrResult analyze(OcrRequest request) {
        byte[] payload = serialize(request);
        if (payload.length > maxRequestBytes) {
            throw new DomainException("OCR_REQUEST_TOO_LARGE",
                "本次分析的图像体积超过上限，请分批提交", HttpStatus.PAYLOAD_TOO_LARGE);
        }
        OcrResult result;
        try {
            result = client.post()
                .uri(analyzePath)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(OcrResult.class);
        } catch (RestClientException exception) {
            // 网络、超时、5xx、4xx 统一归为"上游不可用"：这些都可能是一次性的，
            // 交给任务编排做有限重试；而契约问题不可重试，所以不在这里吞掉。
            throw new DomainException("OCR_UNAVAILABLE", "OCR 服务暂时不可用",
                HttpStatus.SERVICE_UNAVAILABLE, exception);
        }
        if (result == null) {
            throw new DomainException("OCR_CONTRACT_INVALID", "OCR 服务返回了空响应", HttpStatus.BAD_GATEWAY);
        }
        result.requireValid();
        return result;
    }

    private byte[] serialize(OcrRequest request) {
        try {
            return objectMapper.writeValueAsBytes(request);
        } catch (JacksonException exception) {
            throw new DomainException("OCR_REQUEST_INVALID", "分析请求无法序列化",
                HttpStatus.INTERNAL_SERVER_ERROR, exception);
        }
    }
}
