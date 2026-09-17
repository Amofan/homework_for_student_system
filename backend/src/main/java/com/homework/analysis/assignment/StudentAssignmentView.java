package com.homework.analysis.assignment;

import java.time.Instant;

/**
 * 学生可见的作业投影。
 *
 * <p>刻意不复用 {@code AssignmentView} + {@code QuestionView}：那两个结构带着标准答案、
 * 可接受答案、评分项和知识点，把它们发给学生端等于把答案给了学生；
 * 这里只保留“我要交哪份作业、什么时候截止、交了几次”所需的信息。
 *
 * <p>也不包含任何跨学生聚合（已提交人数、平均分之类）。学生的列表里只出现他自己的提交摘要，
 * 否则一个学生可以靠刷新列表推断出同学的交卷进度。
 */
public record StudentAssignmentView(
    long id,
    String title,
    String className,
    String teacherName,
    AssignmentStatus status,
    Instant dueAt,
    int questionCount,
    /** 当前学生在这份作业上的提交状态；从未提交时为 {@link #NOT_SUBMITTED}。 */
    String submissionStatus) {

    public static final String NOT_SUBMITTED = "NOT_SUBMITTED";
}
