package com.homework.analysis.exercise;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 一张分层练习单。{@code notices} 承载题库不足等中文提示：
 * 系统只用现有题库组题，不调用模型编造题目，因此题目数量可能少于每层目标数，
 * 必须把实际原因明确告诉教师，而不是静默少给几道题。
 */
public record ExerciseSetView(
    long id,
    long classId,
    String className,
    long sourceAssignmentId,
    String sourceAssignmentTitle,
    String title,
    ExerciseStatus status,
    Instant createdAt,
    Instant approvedAt,
    List<ExerciseItemView> items,
    List<String> notices) {

    /** 每个层级的目标题量。题库不足时实际题量会少于该值，并通过 notices 说明。 */
    public static final int ITEMS_PER_TIER = 5;

    /**
     * 由题目数量反推提示语。
     *
     * <p>提示不落库，而是每次都从题目重新算出：这样生成时返回的提示与事后
     * {@code GET /api/exercises/{id}} 返回的提示不可能不一致。
     */
    public static List<String> shortageNotices(List<ExerciseItemView> items) {
        List<String> notices = new ArrayList<>();
        for (ExerciseTier tier : ExerciseTier.values()) {
            long count = items.stream().filter(item -> item.tier() == tier).count();
            if (count == 0) {
                notices.add(tier.label() + "没有可用题目：来源作业里没有该层知识点的题目");
            } else if (count < ITEMS_PER_TIER) {
                notices.add(tier.label() + "仅生成 " + count + " 题（目标 " + ITEMS_PER_TIER
                    + " 题）：来源作业里该层知识点只有这些题，不用其他层级的题目补足");
            }
        }
        return List.copyOf(notices);
    }
}
