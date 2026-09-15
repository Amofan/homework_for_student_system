package com.homework.analysis.exercise;

import com.homework.analysis.auth.CurrentTeacher;
import com.homework.analysis.shared.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/exercises")
final class ExerciseController {
    /** 官方 Word 文档类型：浏览器据此直接下载，而不是当文本渲染。 */
    private static final MediaType DOCX = MediaType.parseMediaType(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final ExerciseService service;
    private final CurrentTeacher currentTeacher;

    ExerciseController(ExerciseService service, CurrentTeacher currentTeacher) {
        this.service = service;
        this.currentTeacher = currentTeacher;
    }

    @PostMapping
    ApiResponse<ExerciseSetView> generate(@Valid @RequestBody ExerciseGenerationCommand command,
                                          Authentication authentication) {
        return ApiResponse.ok(service.generate(currentTeacher.id(authentication), command));
    }

    @GetMapping
    ApiResponse<List<ExerciseSetView>> list(@RequestParam long classId, Authentication authentication) {
        return ApiResponse.ok(service.list(currentTeacher.id(authentication), classId));
    }

    @GetMapping("/{exerciseId}")
    ApiResponse<ExerciseSetView> get(@PathVariable long exerciseId, Authentication authentication) {
        return ApiResponse.ok(service.requireOwned(currentTeacher.id(authentication), exerciseId));
    }

    @PostMapping("/{exerciseId}/approve")
    ApiResponse<ExerciseSetView> approve(@PathVariable long exerciseId, Authentication authentication) {
        return ApiResponse.ok(service.approve(currentTeacher.id(authentication), exerciseId));
    }

    /** 导出不走统一信封：响应体就是文档字节，只能由浏览器直接保存。 */
    @GetMapping("/{exerciseId}/export.docx")
    ResponseEntity<byte[]> export(@PathVariable long exerciseId, Authentication authentication) {
        byte[] document = service.exportDocx(currentTeacher.id(authentication), exerciseId);
        return ResponseEntity.ok()
            .contentType(DOCX)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"exercise-" + exerciseId + ".docx\"")
            .body(document);
    }
}
