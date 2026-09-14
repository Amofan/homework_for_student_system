package com.homework.analysis.classroom;

import com.homework.analysis.auth.CurrentTeacher;
import com.homework.analysis.shared.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
final class StudentController {
    private final StudentService service;
    private final CurrentTeacher currentTeacher;

    StudentController(StudentService service, CurrentTeacher currentTeacher) {
        this.service = service;
        this.currentTeacher = currentTeacher;
    }

    @GetMapping("/classes/{classId}/students")
    ApiResponse<List<StudentView>> list(@PathVariable long classId, Authentication authentication) {
        return ApiResponse.ok(service.list(currentTeacher.id(authentication), classId));
    }

    @PostMapping("/classes/{classId}/students")
    ApiResponse<StudentView> create(@PathVariable long classId, @Valid @RequestBody StudentRequest request,
                                    Authentication authentication) {
        return ApiResponse.ok(service.create(currentTeacher.id(authentication), classId, request));
    }

    @PutMapping("/students/{studentId}")
    ApiResponse<StudentView> update(@PathVariable long studentId, @Valid @RequestBody StudentRequest request,
                                    Authentication authentication) {
        return ApiResponse.ok(service.update(currentTeacher.id(authentication), studentId, request));
    }

    @DeleteMapping("/students/{studentId}")
    ApiResponse<Map<String, Boolean>> delete(@PathVariable long studentId, Authentication authentication) {
        service.delete(currentTeacher.id(authentication), studentId);
        return ApiResponse.ok(Map.of("deleted", true));
    }
}
