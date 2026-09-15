package com.homework.analysis.evaluation;

/**
 * 论文评测样本的一行：教师与模型在同一道题上的判定，加上双方的耗时与模型用量。
 *
 * <p>只有可选字段允许为 null；必填字段为空的行根本不该进入导出，
 * 否则评测脚本会以“不能为空”整份拒绝。
 *
 * @param caseId 匿名编号。用学生答案主键而非姓名学号，导出物本身不含身份信息
 * @param teacherModified 教师是否改动了模型建议。恒等于 {@code reviewDecision != "ACCEPT"}，
 *                        保留它是因为论文前几节的数字按这一口径统计，删掉会让新旧结果失去可比性
 * @param reviewDecision ACCEPT、MODIFY 或 REJECT。采纳与驳回是两种不同的教学判断，
 *                       只留布尔值会把它们折叠成同一类
 * @param modelName 产出该建议的模型名，供论文交代模型版本
 * @param promptVersion 提示词版本。历史任务行可能没有，此时为空单元格
 */
record GradingCaseRow(
    String caseId,
    Number totalScore,
    Number teacherScore,
    Number aiScore,
    String teacherErrorType,
    String aiErrorType,
    boolean teacherModified,
    Double teacherSeconds,
    Double aiSeconds,
    Integer inputTokens,
    Integer outputTokens,
    String reviewDecision,
    String modelName,
    String promptVersion) {
}
