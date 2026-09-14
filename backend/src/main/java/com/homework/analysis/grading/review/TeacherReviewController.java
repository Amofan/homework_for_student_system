package com.homework.analysis.grading.review;

import com.homework.analysis.auth.CurrentTeacher;
import com.homework.analysis.shared.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/grading")
final class TeacherReviewController {
    private final TeacherReviewService service;
    private final CurrentTeacher currentTeacher;

    TeacherReviewController(TeacherReviewService service, CurrentTeacher currentTeacher) {
        this.service = service;
        this.currentTeacher = currentTeacher;
    }

    @GetMapping("/assignments/{assignmentId}/review-queue")
    ApiResponse<List<ReviewQueueItem>> queue(@PathVariable long assignmentId, Authentication authentication) {
        return ApiResponse.ok(service.queue(currentTeacher.id(authentication), assignmentId));
    }

    @PostMapping("/results/{resultId}/review")
    ApiResponse<ReviewView> review(@PathVariable long resultId, @Valid @RequestBody ReviewCommand command,
                                   Authentication authentication) {
        return ApiResponse.ok(service.review(currentTeacher.id(authentication), resultId, command));
    }
}
