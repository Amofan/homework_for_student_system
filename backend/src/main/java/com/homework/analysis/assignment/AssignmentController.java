package com.homework.analysis.assignment;

import com.homework.analysis.auth.CurrentTeacher;
import com.homework.analysis.shared.api.ApiResponse;
import com.homework.analysis.shared.error.DomainException;
import com.homework.analysis.shared.importing.ImportResult;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 教师端作业接口。
 *
 * <p>同时挂在两个前缀上：{@code /api/teacher/assignments} 是新的标准路径（安全配置按角色
 * 限定教师），{@code /api/assignments} 是旧路径，保留它是为了不打断已经上线的教师端页面与
 * 既有测试。两条路径映射到同一批方法，不存在两套实现需要同步的问题；
 * 待所有调用方迁移到新路径后，旧前缀可以一次性删除。
 */
@RestController
@RequestMapping({"/api/teacher/assignments", "/api/assignments"})
final class AssignmentController {
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;
    private final AssignmentService service;
    private final SubmissionImportService importer;
    private final CurrentTeacher currentTeacher;

    AssignmentController(AssignmentService service, SubmissionImportService importer, CurrentTeacher currentTeacher) {
        this.service = service;
        this.importer = importer;
        this.currentTeacher = currentTeacher;
    }

    @GetMapping
    ApiResponse<List<AssignmentView>> list(Authentication authentication) {
        return ApiResponse.ok(service.list(currentTeacher.id(authentication)));
    }

    @GetMapping("/{assignmentId}")
    ApiResponse<AssignmentView> get(@PathVariable long assignmentId, Authentication authentication) {
        return ApiResponse.ok(service.requireOwned(currentTeacher.id(authentication), assignmentId));
    }

    @PostMapping
    ApiResponse<AssignmentView> create(@Valid @RequestBody AssignmentCommand command, Authentication authentication) {
        return ApiResponse.ok(service.create(currentTeacher.id(authentication), command));
    }

    @PostMapping("/{assignmentId}/publish")
    ApiResponse<AssignmentView> publish(@PathVariable long assignmentId,
                                       @Valid @RequestBody PublishAssignmentCommand command,
                                       Authentication authentication) {
        return ApiResponse.ok(service.publish(currentTeacher.id(authentication), assignmentId, command));
    }

    @PostMapping("/{assignmentId}/answers/import")
    ApiResponse<ImportResult> importAnswers(@PathVariable long assignmentId,
                                            @RequestParam("file") MultipartFile file,
                                            Authentication authentication) throws IOException {
        validateWorkbook(file);
        return ApiResponse.ok(importer.importWorkbook(currentTeacher.id(authentication), assignmentId, file.getInputStream()));
    }

    private static void validateWorkbook(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (file.isEmpty() || file.getSize() > MAX_FILE_SIZE || name == null || !name.toLowerCase().endsWith(".xlsx")) {
            throw new DomainException("WORKBOOK_INVALID", "仅支持不超过 5 MiB 的 .xlsx 文件");
        }
    }
}
