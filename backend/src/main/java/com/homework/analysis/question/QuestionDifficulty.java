package com.homework.analysis.question;

/**
 * 题目难度。分层练习按难度对同一层级内的候选题排序，
 * 数据库以字符串保存，取值集合只由本枚举定义。
 */
public enum QuestionDifficulty {
    BASIC("基础"),
    MEDIUM("中等"),
    ADVANCED("综合");

    private final String label;

    QuestionDifficulty(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
