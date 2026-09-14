package com.homework.analysis.grading;

import com.homework.analysis.auth.CurrentTeacher;
import com.homework.analysis.shared.api.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/grading")
final class GradingController {
    private final GradingOrchestrator orchestrator;
    private final CurrentTeacher currentTeacher;

    GradingController(GradingOrchestrator orchestrator, CurrentTeacher currentTeacher) {
        this.orchestrator = orchestrator;
        this.currentTeacher = currentTeacher;
    }

    @PostMapping("/assignments/{assignmentId}/run")
    ApiResponse<GradingRunResult> run(@PathVariable long assignmentId, Authentication authentication) {
        return ApiResponse.ok(orchestrator.grade(currentTeacher.id(authentication), assignmentId));
    }
}
