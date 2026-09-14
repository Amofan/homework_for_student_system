package com.homework.analysis.question;

import com.homework.analysis.auth.CurrentTeacher;
import com.homework.analysis.shared.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge-points")
final class KnowledgePointController {
    private final KnowledgePointService service;
    private final CurrentTeacher currentTeacher;

    KnowledgePointController(KnowledgePointService service, CurrentTeacher currentTeacher) {
        this.service = service;
        this.currentTeacher = currentTeacher;
    }

    @GetMapping
    ApiResponse<List<KnowledgePointView>> list(Authentication authentication) {
        return ApiResponse.ok(service.list(currentTeacher.id(authentication)));
    }

    @PostMapping
    ApiResponse<KnowledgePointView> create(@Valid @RequestBody KnowledgePointRequest request,
                                           Authentication authentication) {
        return ApiResponse.ok(service.create(currentTeacher.id(authentication), request));
    }
}
