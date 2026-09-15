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

    /**
     * 浏览器默认只把少数几个响应头交给前端脚本，跨域部署时这三个自定义计数头
     * 必须显式暴露，否则页面读到的是 undefined，剔除数就永远显示成 0。
     * Content-Disposition 一并暴露，前端要靠它取服务端给的文件名。
     */
    private static final String EXPOSED_HEADERS = "Content-Disposition, X-Evaluation-Reviewed, "
        + "X-Evaluation-Exported, X-Evaluation-Skipped-Missing-Ai-Error";

    private final EvaluationExportService service;
    private final CurrentTeacher currentTeacher;

    EvaluationExportController(EvaluationExportService service, CurrentTeacher currentTeacher) {
        this.service = service;
        this.currentTeacher = currentTeacher;
    }

    @GetMapping("/assignments/{assignmentId}/grading-cases.csv")
    ResponseEntity<byte[]> exportGradingCases(@PathVariable long assignmentId, Authentication authentication) {
        EvaluationExport export = service.exportGradingCases(currentTeacher.id(authentication), assignmentId);
        return ResponseEntity.ok()
            .contentType(CSV)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"grading-cases-" + assignmentId + ".csv\"")
            .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, EXPOSED_HEADERS)
            .header("X-Evaluation-Reviewed", String.valueOf(export.reviewedAiCount()))
            .header("X-Evaluation-Exported", String.valueOf(export.exportedCount()))
            .header("X-Evaluation-Skipped-Missing-Ai-Error",
                String.valueOf(export.skippedMissingAiErrorCount()))
            .body(export.csv().getBytes(StandardCharsets.UTF_8));
    }
}
