package com.homework.analysis.evaluation;

/**
 * 一次评测导出的结果：CSV 正文加上三个完整性计数。
 *
 * <p>把计数与正文绑在一个返回值里，是为了让"剔除了几条"无法被调用方忽略。
 * 早先只有服务端一条 WARN 日志，教师导出后拿到的文件里看不出少了样本，
 * 论文的样本量也就无从交代。
 *
 * @param csv 可直接作为 {@code evaluate_grading.py --grading} 输入的 CSV 正文
 * @param reviewedAiCount 已复核的模型样本总数，即剔除前的候选行数
 * @param exportedCount 实际写进 CSV 的行数
 * @param skippedMissingAiErrorCount 因缺少模型原始错因而被剔除的行数
 */
public record EvaluationExport(
    String csv,
    int reviewedAiCount,
    int exportedCount,
    int skippedMissingAiErrorCount) {
}
