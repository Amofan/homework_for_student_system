package com.homework.analysis.analytics;

import com.homework.analysis.auth.CurrentTeacher;
import com.homework.analysis.shared.api.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/analytics")
final class AnalyticsController {
    private final AnalyticsService service;
    private final CurrentTeacher currentTeacher;

    AnalyticsController(AnalyticsService service, CurrentTeacher currentTeacher) {
        this.service = service;
        this.currentTeacher = currentTeacher;
    }

    @GetMapping("/classes/{classId}/mastery")
    ApiResponse<List<KnowledgeMastery>> mastery(@PathVariable long classId, @RequestParam long assignmentId,
                                                Authentication authentication) {
        return ApiResponse.ok(service.mastery(currentTeacher.id(authentication), classId, assignmentId));
    }

    @GetMapping("/assignments/{assignmentId}/errors")
    ApiResponse<List<ErrorRanking>> errors(@PathVariable long assignmentId, Authentication authentication) {
        return ApiResponse.ok(service.errorRanking(currentTeacher.id(authentication), assignmentId));
    }
}
