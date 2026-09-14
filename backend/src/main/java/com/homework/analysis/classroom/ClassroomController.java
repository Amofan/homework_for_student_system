package com.homework.analysis.classroom;

import com.homework.analysis.auth.CurrentTeacher;
import com.homework.analysis.shared.api.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/classes")
final class ClassroomController {
    private final ClassroomService service;
    private final CurrentTeacher currentTeacher;

    ClassroomController(ClassroomService service, CurrentTeacher currentTeacher) {
        this.service = service;
        this.currentTeacher = currentTeacher;
    }

    @GetMapping
    ApiResponse<List<ClassroomView>> list(Authentication authentication) {
        return ApiResponse.ok(service.list(currentTeacher.id(authentication)));
    }

    @GetMapping("/{classId}")
    ApiResponse<ClassroomView> get(@PathVariable long classId, Authentication authentication) {
        return ApiResponse.ok(service.get(currentTeacher.id(authentication), classId));
    }

    @PostMapping
    ApiResponse<ClassroomView> create(@Valid @RequestBody ClassroomRequest request, Authentication authentication) {
        return ApiResponse.ok(service.create(currentTeacher.id(authentication), request));
    }

    @PutMapping("/{classId}")
    ApiResponse<ClassroomView> update(@PathVariable long classId, @Valid @RequestBody ClassroomRequest request,
                                      Authentication authentication) {
        return ApiResponse.ok(service.update(currentTeacher.id(authentication), classId, request));
    }

    @DeleteMapping("/{classId}")
    ApiResponse<Map<String, Boolean>> delete(@PathVariable long classId, Authentication authentication) {
        service.delete(currentTeacher.id(authentication), classId);
        return ApiResponse.ok(Map.of("deleted", true));
    }
}
