package com.homework.analysis.classroom.account;

/**
 * 一次开通或重置操作的明文凭据。
 *
 * <p>{@code temporaryPassword} 只在本响应中出现一次，数据库只保存 BCrypt 哈希。
 * 幂等重放（对已开通学生再次开通）时该字段为 {@code null}、{@code newlyProvisioned} 为
 * {@code false}，调用方据此判断“没有新密码可交付”。
 */
public record ProvisionedStudentAccount(
    long studentId,
    String studentNo,
    String name,
    String username,
    String temporaryPassword,
    boolean newlyProvisioned) {

    public boolean hasPlaintextPassword() {
        return temporaryPassword != null && !temporaryPassword.isBlank();
    }
}
