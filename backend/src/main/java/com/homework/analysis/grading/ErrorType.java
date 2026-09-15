package com.homework.analysis.grading;

import java.util.Arrays;
import java.util.List;

/**
 * 错因标签的唯一来源。规则评分、AI 提示词和教师复核边界都从这里取值，
 * 避免同一组编码在三个地方各写一份而漂移。
 *
 * <p>{@link #ANSWER_MISMATCH} 只用于客观题规则评分（答案对不上，谈不上错因分析），
 * 因此不允许模型使用，见 {@link #aiCodes()}。</p>
 */
public enum ErrorType {
    CORRECT(true),
    ANSWER_MISMATCH(false),
    CALCULATION_ERROR(true),
    METHOD_ERROR(true),
    CONCEPT_ERROR(true),
    INCOMPLETE(true),
    OTHER(true);

    private final boolean aiAllowed;

    ErrorType(boolean aiAllowed) {
        this.aiAllowed = aiAllowed;
    }

    /** 模型可用的错因编码。顺序稳定，直接作为提示词中的候选集合。 */
    public static List<String> aiCodes() {
        return Arrays.stream(values()).filter(value -> value.aiAllowed)
            .map(Enum::name).toList();
    }

    /** 教师复核边界校验：未知编码必须拒绝，否则论文口径会被自由文本污染。 */
    public static boolean isKnown(String code) {
        if (code == null) return false;
        return Arrays.stream(values()).anyMatch(value -> value.name().equals(code));
    }
}
