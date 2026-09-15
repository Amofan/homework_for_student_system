package com.homework.analysis.evaluation;

/**
 * 论文评测样本的一行：教师与模型在同一道题上的判定，加上双方的耗时与模型用量。
 *
 * <p>只有可选字段允许为 null；必填字段为空的行根本不该进入导出，
 * 否则评测脚本会以“不能为空”整份拒绝。
 *
 * @param caseId 匿名编号。用学生答案主键而非姓名学号，导出物本身不含身份信息
 * @param teacherModified 教师是否改动了模型建议
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
    Integer outputTokens) {
}
