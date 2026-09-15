package com.homework.analysis.grading.ai;

/**
 * 一次模型调用的完整结果：评分建议本身，加上论文离线评测需要的用量与耗时。
 *
 * <p>用量来自响应体的 usage，接口未返回时为 {@code null}——缺失与 0 必须区分，
 * 否则“这次调用没花令牌”和“这次调用没有用量数据”会在统计里混为一谈。
 *
 * @param aiSeconds 本次 HTTP 交换的耗时（秒，保留三位小数）
 */
public record ModelCall(
    AiGradingSuggestion suggestion,
    Integer inputTokens,
    Integer outputTokens,
    double aiSeconds) {
}
