package com.homework.analysis.grading.ai;

import com.homework.analysis.auth.CurrentTeacher;
import com.homework.analysis.shared.api.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/grading/ai-tasks")
final class AiTaskController {
    private final AiGradingTaskWorker worker;
    private final CurrentTeacher currentTeacher;

    AiTaskController(AiGradingTaskWorker worker, CurrentTeacher currentTeacher) {
        this.worker = worker;
        this.currentTeacher = currentTeacher;
    }

    @PostMapping("/assignments/{assignmentId}/process-one")
    ApiResponse<AiTaskProcessResult> processOne(@PathVariable long assignmentId, Authentication authentication) {
        return ApiResponse.ok(worker.processOne(currentTeacher.id(authentication), assignmentId));
    }
}
