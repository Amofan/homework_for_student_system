package com.homework.analysis.document;

import com.homework.analysis.auth.CurrentStudent;
import com.homework.analysis.auth.CurrentTeacher;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * 私有文件的受保护读取。
 *
 * <p>两条路径分开写而不是共用一个 handler：教师与学生的归属校验规则不同，
 * 用请求 URI 去分支判断迟早会有人加错条件而把两边的规则串了。
 *
 * <p>两类响应头是必须的：
 * <ul>
 *   <li>{@code Cache-Control: private, no-store}——响应体可能包含学生答卷，
 *       不允许任何中间层把它缓存下来；</li>
 *   <li>{@code X-Content-Type-Options: nosniff}——防止浏览器把声明为图片的响应
 *       按内容嗅探成可执行类型。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
final class DocumentFileController {

    private final StoredFileService service;
    private final CurrentTeacher currentTeacher;
    private final CurrentStudent currentStudent;

    DocumentFileController(StoredFileService service, CurrentTeacher currentTeacher, CurrentStudent currentStudent) {
        this.service = service;
        this.currentTeacher = currentTeacher;
        this.currentStudent = currentStudent;
    }

    @GetMapping("/teacher/files/{fileId}")
    ResponseEntity<Resource> teacherFile(@PathVariable long fileId, Authentication authentication) {
        return response(service.openForTeacher(currentTeacher.id(authentication), fileId));
    }

    @GetMapping("/student/files/{fileId}")
    ResponseEntity<Resource> studentFile(@PathVariable long fileId, Authentication authentication) {
        return response(service.openForStudent(currentStudent.id(authentication), fileId));
    }

    private static ResponseEntity<Resource> response(StoredFileService.StoredFileDownload download) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .header("X-Content-Type-Options", "nosniff")
            .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(download.originalName()))
            .contentLength(download.sizeBytes())
            .contentType(MediaType.parseMediaType(download.mimeType()))
            .body(new InputStreamResource(download.content()));
    }

    /**
     * 内联展示，不强制下载：这份文件多半是给学生看自己答卷的图片，或者给教师核对的题图。
     *
     * <p>文件名来自数据库里的上传记录，虽然入库时已经清理过，这里仍然再洗一遍——
     * 响应头里出现换行就等于响应头注入，而这条路径的输入源头是用户。
     */
    private static String contentDisposition(String originalName) {
        String cleaned = originalName == null ? "file" : originalName
            .replaceAll("[\\r\\n]", "")
            .replace('"', '_')
            .replace('\\', '_');
        if (cleaned.isBlank()) {
            cleaned = "file";
        }
        return String.format(Locale.ROOT, "inline; filename=\"%s\"", cleaned);
    }
}
