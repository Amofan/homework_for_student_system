package com.homework.analysis.assignment;

import java.time.Instant;
import java.util.List;

/**
 * 教师视角的作业投影。
 *
 * <p>{@code version} 是乐观锁版本号，客户端发布或推进阶段时必须原样带回；
 * {@code publishedAt} 为 {@code null} 表示这份作业还没有发布过，学生看不到它。
 */
public record AssignmentView(
    long id,
    long classId,
    String title,
    AssignmentStatus status,
    Instant publishedAt,
    Instant dueAt,
    int version,
    List<Long> questionIds) {
}
