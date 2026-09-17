package com.homework.analysis.classroom.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 首次改密与自助改密请求。
 *
 * <p>长度上限 72 是 BCrypt 的输入上限：更长的部分会被静默截断，与其让人以为“长密码更安全”，
 * 不如直接拒绝。至少三类的复杂度规则在服务层校验。
 */
public record PasswordChangeCommand(
    @NotBlank @Size(max = 128) String currentPassword,
    @NotBlank @Size(min = 10, max = 72) String newPassword) {
}
