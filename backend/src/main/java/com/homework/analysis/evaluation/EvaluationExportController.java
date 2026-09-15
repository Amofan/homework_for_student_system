package com.homework.analysis.evaluation;

import com.homework.analysis.auth.CurrentTeacher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * 论文离线评测的取数入口：把一次作业里已复核的模型评分样本导出成匿名 CSV。
 *
 * <p>导出物本身就是文档，不带统一响应信封——前端拿到即可直接存盘。
 * 鉴权沿用作业归属校验，教师只能导出自己的作业。
 */
@RestController
@RequestMapping("/api/evaluation")
final class EvaluationExportController {
    private static final MediaType CSV = MediaType.parseMediaType("text/csv; charset=UTF-8");

    private final EvaluationExportService service;
    private final CurrentTeacher currentTeacher;

    EvaluationExportController(EvaluationExportService service, CurrentTeacher currentTeacher) {
        this.service = service;
        this.currentTeacher = currentTeacher;
    }

    @GetMapping("/assignments/{assignmentId}/grading-cases.csv")
    ResponseEntity<byte[]> exportGradingCases(@PathVariable long assignmentId, Authentication authentication) {
        String csv = service.exportGradingCases(currentTeacher.id(authentication), assignmentId);
        return ResponseEntity.ok()
            .contentType(CSV)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"grading-cases-" + assignmentId + ".csv\"")
            .body(csv.getBytes(StandardCharsets.UTF_8));
    }
}
