package com.homework.analysis.question;

public record KnowledgePointView(long id, Long parentId, String code, String name, int grade, boolean active) {
}
