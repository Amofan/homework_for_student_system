package com.homework.analysis.question;

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
@RequestMapping("/api/questions")
final class QuestionController {
    private final QuestionService service;
    private final CurrentTeacher currentTeacher;

    QuestionController(QuestionService service, CurrentTeacher currentTeacher) {
        this.service = service;
        this.currentTeacher = currentTeacher;
    }

    @GetMapping
    ApiResponse<List<QuestionView>> list(Authentication authentication) {
        return ApiResponse.ok(service.list(currentTeacher.id(authentication)));
    }

    @GetMapping("/{questionId}")
    ApiResponse<QuestionView> get(@PathVariable long questionId, Authentication authentication) {
        return ApiResponse.ok(service.requireOwned(currentTeacher.id(authentication), questionId));
    }

    @PostMapping
    ApiResponse<QuestionView> create(@Valid @RequestBody QuestionCommand command, Authentication authentication) {
        return ApiResponse.ok(service.create(currentTeacher.id(authentication), command));
    }
}
