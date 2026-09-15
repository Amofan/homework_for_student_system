package com.homework.analysis.exercise;

import com.homework.analysis.question.QuestionDifficulty;

/**
 * 分层练习的三个层级。层级由来源作业里各知识点的掌握度决定，
 * 枚举顺序即练习单上的呈现顺序：先基础巩固，再方法纠错，最后综合提升。
 *
 * <p>层与层之间不互相借题：本层知识点题量不足时该层就少出几题，
 * 见 {@link ExerciseService} 的选题规则。
 */
public enum ExerciseTier {
    FOUNDATION("基础巩固层", "掌握度低于 60% 的知识点，重建基本概念与运算", QuestionDifficulty.BASIC),
    CORRECTION("方法纠错层", "掌握度 60% 至 80% 的知识点，纠正典型错因与方法偏差", QuestionDifficulty.MEDIUM),
    IMPROVEMENT("综合提升层", "掌握度高于 80% 的知识点，训练综合应用与迁移", QuestionDifficulty.ADVANCED);

    private final String label;
    private final String description;
    private final QuestionDifficulty preferredDifficulty;

    ExerciseTier(String label, String description, QuestionDifficulty preferredDifficulty) {
        this.label = label;
        this.description = description;
        this.preferredDifficulty = preferredDifficulty;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /**
     * 本层最贴合的难度，用于层内排序：基础层从易到难，综合层从难到易。
     * 未指定的难度排在最后，避免历史题目意外占据层内首位。
     */
    public int difficultyRank(QuestionDifficulty difficulty) {
        if (difficulty == preferredDifficulty) return 0;
        if (difficulty == QuestionDifficulty.MEDIUM) return 1;
        if (difficulty == QuestionDifficulty.BASIC) return 2;
        return 3;
    }
}
