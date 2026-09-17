package com.homework.analysis.ocr;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.homework.analysis.shared.error.DomainException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OCR 适配器契约测试。全程只访问本机 WireMock：不联网、不下载模型。
 *
 * <p>请求体上限被刻意调到很小（1000 字节），这样"超限"这条分支不需要真的构造一份
 * 几十 MB 的图像——被测的是"发之前先量体积"这件事，与体积有多大无关。
 */
@SpringBootTest
@ActiveProfiles("test")
class HttpOcrProviderTest {

    private static final String ANALYSIS_PATH = "/v1/documents/analyze";
    private static final String TOKEN = "internal-token-not-a-real-secret";
    private static final long MAX_REQUEST_BYTES = 1000L;

    /** 静态初始化保证 WireMock 在 Spring 读取动态属性之前就已启动。 */
    private static final WireMockServer OCR = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        OCR.start();
    }

    @AfterAll
    static void stopOcr() {
        OCR.stop();
    }

    @DynamicPropertySource
    static void ocrProperties(DynamicPropertyRegistry registry) {
        registry.add("app.ocr.enabled", () -> "true");
        registry.add("app.ocr.base-url", OCR::baseUrl);
        registry.add("app.ocr.analyze-path", () -> ANALYSIS_PATH);
        registry.add("app.ocr.token", () -> TOKEN);
        registry.add("app.ocr.timeout-seconds", () -> "5");
        registry.add("app.ocr.max-request-bytes", () -> String.valueOf(MAX_REQUEST_BYTES));
        registry.add("app.ocr.max-attempts", () -> "4");
        registry.add("app.ocr.processing-version", () -> "1");
        registry.add("app.ocr.model-version", () -> "3.7.0");
    }

    @Autowired OcrProvider provider;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void resetStubs() {
        OCR.resetAll();
    }

    @Test
    void 成功响应时按契约发送请求并返回通过校验的结果() {
        OCR.stubFor(post(urlEqualTo(ANALYSIS_PATH)).willReturn(okJson(validResponse())));

        OcrResult result = provider.analyze(request());

        assertThat(result.engine()).isEqualTo("paddleocr");
        assertThat(result.modelVersion()).isEqualTo("3.7.0");
        assertThat(result.pages()).hasSize(1);
        assertThat(result.pages().getFirst().regions().getFirst().externalId()).isEqualTo("p1-r1");

        // 内部令牌只在请求头里，且请求体必须自报 schemaVersion 与文档类型。
        OCR.verify(postRequestedFor(urlEqualTo(ANALYSIS_PATH))
            .withHeader("Authorization", equalTo("Bearer " + TOKEN))
            .withRequestBody(equalToJson("""
                {"schemaVersion":"v1","documentKind":"EXAM_PAPER","processingVersion":1,
                 "pages":[{"pageNo":1,"contentBase64":"AAAA"}]}
                """, true, true)));
    }

    @Test
    void 服务不可用时映射为可重试的错误码() {
        OCR.stubFor(post(urlEqualTo(ANALYSIS_PATH)).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> provider.analyze(request()))
            .isInstanceOf(DomainException.class)
            .extracting(exception -> ((DomainException) exception).code())
            .isEqualTo("OCR_UNAVAILABLE");
    }

    @Test
    void 服务返回空响应体时判为契约不合法() {
        OCR.stubFor(post(urlEqualTo(ANALYSIS_PATH)).willReturn(aResponse().withStatus(200).withBody("")));

        assertThatThrownBy(() -> provider.analyze(request()))
            .isInstanceOf(DomainException.class)
            .extracting(exception -> ((DomainException) exception).code())
            .isEqualTo("OCR_CONTRACT_INVALID");
    }

    @Test
    void 未知schema版本判为契约不合法而不是重试() {
        OCR.stubFor(post(urlEqualTo(ANALYSIS_PATH))
            .willReturn(okJson(validResponse().replace("\"v1\"", "\"v2\""))));

        assertThatThrownBy(() -> provider.analyze(request()))
            .isInstanceOf(DomainException.class)
            .extracting(exception -> ((DomainException) exception).code())
            .isEqualTo("OCR_CONTRACT_INVALID");
    }

    @Test
    void 坐标越界判为契约不合法() {
        OCR.stubFor(post(urlEqualTo(ANALYSIS_PATH))
            .willReturn(okJson(validResponse().replace("\"x\": 0.1", "\"x\": 1.4"))));

        assertThatThrownBy(() -> provider.analyze(request()))
            .isInstanceOf(DomainException.class)
            .extracting(exception -> ((DomainException) exception).code())
            .isEqualTo("OCR_CONTRACT_INVALID");
    }

    /**
     * 体积超限在本地就失败。
     *
     * <p>把检查放在发送之后，等于把一份几十 MB 的请求推给对端再等超时——对端的资源
     * 和我们的等待时间都白花了。
     */
    @Test
    void 请求体超过上限时本地拒绝且不发请求() {
        OcrRequest oversized = new OcrRequest("EXAM_PAPER", 1,
            List.of(new OcrRequest.Page(1, "A".repeat((int) MAX_REQUEST_BYTES * 2))));

        assertThatThrownBy(() -> provider.analyze(oversized))
            .isInstanceOf(DomainException.class)
            .extracting(exception -> ((DomainException) exception).code())
            .isEqualTo("OCR_REQUEST_TOO_LARGE");

        OCR.verify(0, postRequestedFor(urlEqualTo(ANALYSIS_PATH)));
    }

    private static OcrRequest request() {
        return new OcrRequest("EXAM_PAPER", 1, List.of(new OcrRequest.Page(1, "AAAA")));
    }

    private static String validResponse() {
        return """
            {
              "schemaVersion": "v1",
              "engine": "paddleocr",
              "modelVersion": "3.7.0",
              "pages": [{
                "pageNo": 1,
                "width": 2480,
                "height": 3508,
                "regions": [{
                  "externalId": "p1-r1",
                  "type": "QUESTION_TEXT",
                  "x": 0.1, "y": 0.2, "width": 0.7, "height": 0.08,
                  "text": "如图，AB∥CD…",
                  "latex": null,
                  "confidence": 0.93
                }]
              }]
            }
            """;
    }

    /** 用一次额外断言确认 WireMock 的请求体断言与 Jackson 解析一致，避免契约漂移。 */
    @Test
    void 请求体可被同一套Jackson规则解析() throws Exception {
        OCR.stubFor(post(urlEqualTo(ANALYSIS_PATH)).willReturn(okJson(validResponse())));

        provider.analyze(request());

        JsonNode sent = objectMapper.readTree(
            OCR.getAllServeEvents().getFirst().getRequest().getBodyAsString());
        assertThat(sent.get("schemaVersion").asText()).isEqualTo("v1");
        assertThat(sent.get("pages").get(0).get("pageNo").asInt()).isEqualTo(1);
    }
}
