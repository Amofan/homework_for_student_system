package com.homework.analysis.assignment;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 作业生命周期状态。
 *
 * <p>状态只能沿一条线向前走：{@code DRAFT/OCR_REVIEW → PUBLISHED → SUBMITTING → GRADING
 * → REVIEWING → COMPLETED}。刻意不提供 {@code canTransitionTo} 之外的“任意设置状态”入口，
 * 否则迟早会有人从接口层直接把作业改成 {@code COMPLETED}，跳过评分与复核。
 *
 * <p>唯一允许的向后动作是“教师退回”，它作用在提交版本上而不是作业状态上，
 * 因此不在本枚举里开口子。
 */
public enum AssignmentStatus {
    DRAFT,
    OCR_REVIEW,
    PUBLISHED,
    SUBMITTING,
    GRADING,
    REVIEWING,
    COMPLETED;

    /**
     * 历史取值。
     *
     * <p>教师端 Excel 导入答案属于旧链路，它写入的 {@code IMPORTED} 表示“已有答案待批改”，
     * 语义上等价于 {@code SUBMITTING}。迁移前的正式库与演示库里都有这种行，
     * 直接按未知状态抛错会让历史作业全部打不开，所以在这里映射一次。
     */
    private static final Map<String, AssignmentStatus> LEGACY_NAMES = Map.of("IMPORTED", SUBMITTING);

    private static final Map<AssignmentStatus, Set<AssignmentStatus>> ALLOWED_NEXT = Map.of(
        DRAFT, EnumSet.of(PUBLISHED),
        OCR_REVIEW, EnumSet.of(PUBLISHED),
        PUBLISHED, EnumSet.of(SUBMITTING),
        SUBMITTING, EnumSet.of(GRADING),
        GRADING, EnumSet.of(REVIEWING),
        REVIEWING, EnumSet.of(COMPLETED),
        COMPLETED, EnumSet.noneOf(AssignmentStatus.class));

    public static AssignmentStatus parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("作业状态为空");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        AssignmentStatus legacy = LEGACY_NAMES.get(normalized);
        if (legacy != null) {
            return legacy;
        }
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            // 库里出现无法识别的状态属于数据损坏，不是用户错误：让它在 500 里暴露出来，
            // 而不是悄悄退回某个默认状态继续跑。
            throw new IllegalArgumentException("无法识别的作业状态：" + raw, exception);
        }
    }

    public boolean canTransitionTo(AssignmentStatus next) {
        return ALLOWED_NEXT.getOrDefault(this, Set.of()).contains(next);
    }

    /**
     * 是否已进入学生可见范围。
     *
     * <p>仅作兜底判断：学生查询以 {@code assignment.published_at is not null} 为准，
     * 因为“已发布”是一次性的时间事实，不会被后续状态推进抹掉。
     */
    public boolean visibleToStudents() {
        return this != DRAFT && this != OCR_REVIEW;
    }
}
