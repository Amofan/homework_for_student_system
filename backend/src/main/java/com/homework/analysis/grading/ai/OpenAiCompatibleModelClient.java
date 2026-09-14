package com.homework.analysis.grading.ai;

import com.homework.analysis.shared.error.DomainException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "app.model.enabled", havingValue = "true")
public final class OpenAiCompatibleModelClient implements AiModelClient {
    private final RestClient client;
    private final ModelProperties properties;
    private final GradingPromptBuilder promptBuilder;
    private final AiResultValidator validator;
    private final ObjectMapper objectMapper;

    OpenAiCompatibleModelClient(RestClient modelRestClient, ModelProperties properties,
                                GradingPromptBuilder promptBuilder, AiResultValidator validator,
                                ObjectMapper objectMapper) {
        if (properties.apiKey() == null || properties.apiKey().isBlank() ||
            properties.name() == null || properties.name().isBlank()) {
            throw new IllegalStateException("启用大模型时必须配置 MODEL_API_KEY 和 MODEL_NAME");
        }
        this.client = modelRestClient;
        this.properties = properties;
        this.promptBuilder = promptBuilder;
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiGradingSuggestion grade(AiGradingRequest request) {
        Map<String, Object> body = Map.of(
            "model", properties.name(),
            "input", promptBuilder.build(request),
            "store", false,
            "text", Map.of("format", Map.of(
                "type", "json_schema",
                "name", "grading_suggestion",
                "strict", true,
                "schema", responseSchema())));
        try {
            JsonNode response = client.post()
                .uri(properties.apiPath())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + properties.apiKey())
                .body(body)
                .retrieve()
                .body(JsonNode.class);
            String text = extractOutputText(response);
            AiGradingSuggestion suggestion = objectMapper.readValue(text, AiGradingSuggestion.class);
            return validator.validate(request, suggestion);
        } catch (DomainException exception) {
            throw exception;
        } catch (Exception exception) {
            // 保留原始异常作为 cause：对外消息保持稳定且不含响应体与密钥，
            // 但丢掉根因会让连接层与解析层故障无法区分，排障只能靠猜测。
            throw new DomainException("MODEL_CALL_FAILED", "大模型调用失败，请稍后重试", exception);
        }
    }

    private static String extractOutputText(JsonNode response) {
        if (response == null) throw new IllegalStateException("模型返回空响应");
        for (JsonNode item : response.path("output")) {
            for (JsonNode content : item.path("content")) {
                if ("output_text".equals(content.path("type").asText()) && !content.path("text").asText().isBlank()) {
                    return content.path("text").asText();
                }
            }
        }
        throw new IllegalStateException("模型响应缺少 output_text");
    }

    private static Map<String, Object> responseSchema() {
        Map<String, Object> scoreDetail = Map.of(
            "type", "object",
            "additionalProperties", false,
            "required", List.of("rubricId", "score", "evidence"),
            "properties", Map.of(
                "rubricId", Map.of("type", "integer"),
                "score", Map.of("type", "integer"),
                "evidence", Map.of("type", "string")));
        return Map.of(
            "type", "object",
            "additionalProperties", false,
            "required", List.of("suggestedScore", "scoreDetails", "errorType", "teacherExplanation", "studentFeedback", "needsTeacherReview"),
            "properties", Map.of(
                "suggestedScore", Map.of("type", "integer"),
                "scoreDetails", Map.of("type", "array", "items", scoreDetail),
                "errorType", Map.of("type", "string"),
                "teacherExplanation", Map.of("type", "string"),
                "studentFeedback", Map.of("type", "string"),
                "needsTeacherReview", Map.of("type", "boolean")));
    }
}
