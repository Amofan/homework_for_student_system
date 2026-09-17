package com.homework.analysis.classroom;

/**
 * 学生名册行。
 *
 * <p>账号字段在未开通时整组为 {@code null}，被 {@code non_null} 序列化策略省略；
 * 前端因此可以只判断 {@code accountUsername} 是否存在来决定显示“开通账号”还是“重置密码”。
 */
public record StudentView(
    long id,
    long classId,
    String studentNo,
    String name,
    String accountUsername,
    String accountStatus) {
}
