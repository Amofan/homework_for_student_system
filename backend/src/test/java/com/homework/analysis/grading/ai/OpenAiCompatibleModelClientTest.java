package com.homework.analysis.grading.ai;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
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
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 大模型适配器契约测试。全程只访问本机 WireMock，不调用公网，也不需要真实模型密钥。
 */
@SpringBootTest
@ActiveProfiles("test")
class OpenAiCompatibleModelClientTest {

    private static final String API_PATH = "/v1/responses";
    private static final String MODEL_NAME = "junior-math-grader";
    private static final String API_KEY = "test-key-not-a-real-secret";

    /** 静态初始化保证 WireMock 在 Spring 读取动态属性之前就已启动。 */
    private static final WireMockServer MODEL = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        MODEL.start();
    }

    @AfterAll
    static void stopModel() {
        MODEL.stop();
    }

    @DynamicPropertySource
    static void modelProperties(DynamicPropertyRegistry registry) {
        registry.add("app.model.enabled", () -> "true");
        registry.add("app.model.base-url", MODEL::baseUrl);
        registry.add("app.model.api-path", () -> API_PATH);
        registry.add("app.model.name", () -> MODEL_NAME);
        registry.add("app.model.api-key", () -> API_KEY);
        registry.add("app.model.timeout-seconds", () -> "2");
    }

    @Autowired AiModelClient modelClient;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void resetStubs() {
        MODEL.resetAll();
    }

    @Test
    void 成功响应时按契约发送请求并返回通过校验的建议() throws Exception {
        MODEL.stubFor(post(urlEqualTo(API_PATH)).willReturn(okJson(successResponse())));

        AiGradingSuggestion suggestion = modelClient.grade(request());

        assertThat(suggestion.suggestedScore()).isEqualTo(6);
        assertThat(suggestion.errorType()).isEqualTo("CORRECT");
        assertThat(suggestion.needsTeacherReview()).isTrue();

        LoggedRequest sent = lastRequest();
        assertThat(sent.getMethod().value()).isEqualTo("POST");
        assertThat(sent.getUrl()).isEqualTo(API_PATH);
        assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer " + API_KEY);

        JsonNode body = objectMapper.readTree(sent.getBodyAsString());
        assertThat(body.path("model").asText()).isEqualTo(MODEL_NAME);
        assertThat(body.path("store").asBoolean()).isFalse();
        assertThat(body.path("text").path("format").path("type").asText()).isEqualTo("json_schema");
        assertThat(body.path("text").path("format").path("strict").asBoolean()).isTrue();
        assertThat(body.path("text").path("format").path("schema").path("required").size()).isPositive();
    }

    @Test
    void 请求体只包含匿名标识且不含任何身份信息() throws Exception {
        MODEL.stubFor(post(urlEqualTo(API_PATH)).willReturn(okJson(successResponse())));

        modelClient.grade(request());

        String payload = lastRequest().getBodyAsString();
        assertThat(payload).contains("answer-611");
        assertThat(payload).doesNotContain("张三", "李四", "20260001", "七年级一班", API_KEY);
        JsonNode body = objectMapper.readTree(payload);
        assertThat(body.path("input").asText()).doesNotContain("张三", "20260001", "七年级一班");
    }

    @Test
    void 限流与服务器错误统一转为稳定的领域错误() {
        assertFailsWithModelCallFailed(429, "{\"error\":{\"message\":\"rate limited\"}}");
        assertFailsWithModelCallFailed(500, "{\"error\":{\"message\":\"internal\"}}");
    }

    @Test
    void 缺少output_text时转为稳定的领域错误() {
        assertFailsWithModelCallFailed(200, "{\"output\":[{\"content\":[{\"type\":\"reasoning\",\"text\":\"\"}]}]}");
        assertFailsWithModelCallFailed(200, "{\"output\":[]}");
    }

    @Test
    void 返回内容不是合法JSON时转为稳定的领域错误() {
        assertFailsWithModelCallFailed(200, responseWithText("这不是 JSON"));
    }

    @Test
    void 读取超时不会泄漏响应体且转为稳定的领域错误() {
        MODEL.stubFor(post(urlEqualTo(API_PATH))
            .willReturn(aResponse().withFixedDelay(3_000).withBody(successResponse())));

        assertThatThrownBy(() -> modelClient.grade(request()))
            .isInstanceOf(DomainException.class)
            .hasMessage("大模型调用失败，请稍后重试")
            .extracting(exception -> ((DomainException) exception).code())
            .isEqualTo("MODEL_CALL_FAILED");
    }

    @Test
    void 分项合计不等于总分时保留校验错误码() {
        MODEL.stubFor(post(urlEqualTo(API_PATH)).willReturn(okJson(responseWithSuggestion(Map.of(
            "suggestedScore", 8,
            "scoreDetails", List.of(Map.of("rubricId", 1, "score", 3, "evidence", "部分正确")),
            "errorType", "CORRECT",
            "teacherExplanation", "说明",
            "studentFeedback", "反馈",
            "needsTeacherReview", true)))));

        assertThatThrownBy(() -> modelClient.grade(request()))
            .isInstanceOf(DomainException.class)
            .extracting(exception -> ((DomainException) exception).code())
            .isEqualTo("AI_SCORE_DETAIL_MISMATCH");
    }

    @Test
    void 未知错因标签时保留校验错误码() {
        MODEL.stubFor(post(urlEqualTo(API_PATH)).willReturn(okJson(responseWithSuggestion(Map.of(
            "suggestedScore", 6,
            "scoreDetails", List.of(Map.of("rubricId", 1, "score", 6, "evidence", "正确")),
            "errorType", "NOT_A_REAL_LABEL",
            "teacherExplanation", "说明",
            "studentFeedback", "反馈",
            "needsTeacherReview", false)))));

        assertThatThrownBy(() -> modelClient.grade(request()))
            .isInstanceOf(DomainException.class)
            .extracting(exception -> ((DomainException) exception).code())
            .isEqualTo("AI_ERROR_TYPE_INVALID");
    }

    private void assertFailsWithModelCallFailed(int status, String body) {
        MODEL.stubFor(post(urlEqualTo(API_PATH)).willReturn(aResponse().withStatus(status).withBody(body)));
        int servedBefore = MODEL.getAllServeEvents().size();

        assertThatThrownBy(() -> modelClient.grade(request()))
            .isInstanceOf(DomainException.class)
            .hasMessage("大模型调用失败，请稍后重试")
            .satisfies(exception -> assertThat(((DomainException) exception).code()).isEqualTo("MODEL_CALL_FAILED"))
            .satisfies(exception -> assertThat(exception.getMessage()).doesNotContain(API_KEY, "rate limited", "internal"))
            .satisfies(exception -> assertThat(exception.getCause())
                .as("必须保留原始异常，否则连接层与解析层故障无法区分")
                .isNotNull());

        // 必须确认请求真的被服务端处理过：否则连接层失败同样会抛出 MODEL_CALL_FAILED，
        // 让本断言在“适配器根本没发对请求”时依然变绿，掩盖真实缺陷。
        assertThat(MODEL.getAllServeEvents())
            .as("请求应当到达模型服务端，本用例验证的是对服务端响应的转换而非连接层失败")
            .hasSize(servedBefore + 1);
    }

    private LoggedRequest lastRequest() {
        return MODEL.getAllServeEvents().getFirst().getRequest();
    }

    private static AiGradingRequest request() {
        return new AiGradingRequest("answer-611", "解方程 $2x+1=5$", "x=2", "x=2", 10,
            List.of(new AiGradingRequest.Rubric(1, "列式", "方程正确", 6)),
            List.of("CORRECT", "CALCULATION_ERROR", "METHOD_ERROR", "CONCEPT_ERROR", "INCOMPLETE", "OTHER"));
    }

    private String successResponse() {
        return responseWithSuggestion(Map.of(
            "suggestedScore", 6,
            "scoreDetails", List.of(Map.of("rubricId", 1, "score", 6, "evidence", "列式正确")),
            "errorType", "CORRECT",
            "teacherExplanation", "过程完整",
            "studentFeedback", "保持书写规范",
            "needsTeacherReview", true));
    }

    /** 按 Responses 风格包装模型输出，text 字段内是模型返回的 JSON 字符串。 */
    private String responseWithSuggestion(Map<String, Object> suggestion) {
        return responseWithText(objectMapper.writeValueAsString(suggestion));
    }

    private String responseWithText(String text) {
        return objectMapper.writeValueAsString(Map.of("output", List.of(
            Map.of("content", List.of(Map.of("type", "output_text", "text", text))))));
    }
}
