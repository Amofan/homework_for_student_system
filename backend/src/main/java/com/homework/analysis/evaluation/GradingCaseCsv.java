package com.homework.analysis.evaluation;

import java.util.List;

/**
 * 把一批匿名评分样本写成论文评测脚本能直接读的 CSV。
 *
 * <p>列顺序与 {@code evaluation/README.md} 的契约一致，多一列少一列都会让脚本报错，
 * 因此这里把列名与取值放在同一处，改口径时只改这个文件。
 *
 * <p>刻意不写 UTF-8 BOM：Excel 需要 BOM 才不会把中文显示成乱码，但本文件的取值
 * 全是编号、标签与数字（不含中文），而 BOM 会让 Python 的 csv 模块把第一列读成
 * {@code ﻿case_id}，评测脚本随即报“缺少必需列”。两害相权，选不带 BOM。
 */
final class GradingCaseCsv {
    static final String HEADER = "case_id,total_score,teacher_score,ai_score,teacher_error_type,"
        + "ai_error_type,teacher_modified,teacher_seconds,ai_seconds,input_tokens,output_tokens";

    private GradingCaseCsv() {
    }

    static String render(List<GradingCaseRow> rows) {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        for (GradingCaseRow row : rows) {
            csv.append(row.caseId()).append(',')
                .append(number(row.totalScore())).append(',')
                .append(number(row.teacherScore())).append(',')
                .append(number(row.aiScore())).append(',')
                .append(label(row.teacherErrorType())).append(',')
                .append(label(row.aiErrorType())).append(',')
                .append(row.teacherModified() ? "true" : "false").append(',')
                .append(optional(row.teacherSeconds())).append(',')
                .append(optional(row.aiSeconds())).append(',')
                .append(optional(row.inputTokens())).append(',')
                .append(optional(row.outputTokens())).append('\n');
        }
        return csv.toString();
    }

    /** 缺失写成空单元格：脚本把空读作“缺失”，写成 0 会被当成真实观测值。 */
    private static String optional(Number value) {
        return value == null ? "" : number(value);
    }

    /** 分数按整数输出，避免 6 变成 6.0 让人怀疑满分口径。 */
    private static String number(Number value) {
        if (value instanceof Double || value instanceof Float) {
            double raw = value.doubleValue();
            if (raw == Math.rint(raw)) return String.valueOf((long) raw);
        }
        return value.toString();
    }

    private static String label(String value) {
        return value == null ? "" : quote(value);
    }

    /**
     * 错因标签来自固定集合（见 {@code AiGradingTaskWorker.ERROR_TYPES}），本来不含逗号与引号，
     * 但 CSV 的转义规则不该依赖“当前取值恰好安全”：一旦有人加了带逗号的标签，
     * 少一次转义就会让整份样本错位，而错位是静默的。
     */
    private static String quote(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
