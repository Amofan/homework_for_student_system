package com.homework.analysis.grading.review;

public record ReviewView(long reviewId, long resultId, ReviewDecision decision, int finalScore,
                         String finalErrorType, String feedback, String reason) {
}
