package com.homework.analysis.assignment;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

/**
 * 发布作业。
 *
 * <p>{@code version} 必填且由客户端带上它读到的值：发布是并发敏感操作，两位教师同时点
 * “发布”时，缺少版本号就只能靠时间先后决定结果，后一次会静默覆盖前一次设置的截止时间。
 * 用乐观锁把冲突变成一次明确的“请刷新后重试”。
 *
 * <p>{@code dueAt} 可选，为 ISO-8601 瞬时；为 {@code null} 表示不设截止时间。
 */
public record PublishAssignmentCommand(
    @NotNull @PositiveOrZero Integer version,
    Instant dueAt) {
}
