package com.homework.analysis.grading;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorTypeTest {
    @Test
    void 固定完整标签全集() {
        assertThat(Arrays.stream(ErrorType.values()).map(Enum::name))
            .containsExactlyInAnyOrder("CORRECT", "ANSWER_MISMATCH", "CALCULATION_ERROR",
                "METHOD_ERROR", "CONCEPT_ERROR", "INCOMPLETE", "OTHER");
    }

    @Test
    void 模型不允许使用客观题专属标签() {
        assertThat(ErrorType.aiCodes())
            .containsExactly("CORRECT", "CALCULATION_ERROR", "METHOD_ERROR",
                "CONCEPT_ERROR", "INCOMPLETE", "OTHER")
            .doesNotContain("ANSWER_MISMATCH");
    }
}
