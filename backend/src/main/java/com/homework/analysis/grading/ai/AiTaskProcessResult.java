package com.homework.analysis.grading.ai;

public record AiTaskProcessResult(boolean processed, Long taskId, String status, String message) {
}
