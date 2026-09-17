package com.homework.analysis.document;

import com.homework.analysis.shared.error.DomainException;
import com.homework.analysis.shared.jdbc.GeneratedKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

/**
 * 文件元数据与对象存储之间的唯一入口。
 *
 * <p>写入的顺序是「先对象、后元数据」：反过来的话，元数据行会在对象还没落地时就存在，
 * 读取方看到一个指向空气的文件。写入失败时的补偿也随之明确——删掉刚放上去的对象。
 *
 * <p>读取一律经过归属校验，且校验写在 SQL 里而不是先查出来再在内存里比较：
 * 内存比较一旦有人漏写一处判断，泄露就是静默的。
 */
@Service
public class StoredFileService {

    private static final Logger log = LoggerFactory.getLogger(StoredFileService.class);

    private final JdbcClient jdbc;
    private final FileStorage storage;
    private final UploadFileInspector inspector;

    StoredFileService(JdbcClient jdbc, FileStorage storage, UploadFileInspector inspector) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.inspector = inspector;
    }

    public StoredFileView storeTeacherFile(long teacherId, String originalName, String contentType, byte[] content) {
        return store(teacherId, null, "teachers/" + teacherId + "/uploads/", originalName, contentType, content);
    }

    /** 学生上传：归属教师取自学生所在班级，学生自己无法指定。 */
    public StoredFileView storeStudentFile(long studentId, String originalName, String contentType, byte[] content) {
        long teacherId = jdbc.sql("""
                select c.teacher_id from student s
                join school_class c on c.id = s.class_id
                where s.id = :studentId and s.deleted_at is null and c.deleted_at is null
                """)
            .param("studentId", studentId)
            .query(Long.class)
            .optional()
            .orElseThrow(() -> new DomainException("STUDENT_NOT_FOUND", "学生不存在", HttpStatus.NOT_FOUND));
        return store(teacherId, studentId, "students/" + studentId + "/uploads/", originalName, contentType, content);
    }

    /**
     * 校验、落对象、写元数据。
     *
     * <p>存储键里的随机段由服务端生成：用户文件名既不含在内，也无法预测下一个键，
     * 于是"猜别人的文件地址"这条路不存在。
     */
    private StoredFileView store(long teacherId, Long studentId, String keyPrefix,
                                 String originalName, String contentType, byte[] content) {
        UploadFileInspector.InspectedFile inspected = inspector.inspect(originalName, contentType, content);
        String storageKey = keyPrefix + UUID.randomUUID() + extensionSuffix(inspected.originalName());

        FileStorage.StoredObject object;
        try {
            object = storage.put(new FileStorage.StorageWrite(
                storageKey, inspected.mimeType(), content.length, new ByteArrayInputStream(content)));
        } catch (IOException exception) {
            throw new DomainException("STORAGE_WRITE_FAILED", "文件写入失败，请稍后重试",
                HttpStatus.SERVICE_UNAVAILABLE, exception);
        }

        try {
            long fileId = GeneratedKeys.insert(jdbc, """
                insert into stored_file(teacher_id, student_id, storage_key, original_name, mime_type,
                                        size_bytes, sha256, width, height, status)
                values (:teacherId, :studentId, :storageKey, :originalName, :mimeType,
                        :sizeBytes, :sha256, :width, :height, 'ACTIVE')
                """, statement -> statement
                .param("teacherId", teacherId)
                .param("studentId", studentId)
                .param("storageKey", object.storageKey())
                .param("originalName", inspected.originalName())
                .param("mimeType", inspected.mimeType())
                .param("sizeBytes", object.sizeBytes())
                .param("sha256", object.sha256())
                .param("width", inspected.width() == 0 ? null : inspected.width())
                .param("height", inspected.height() == 0 ? null : inspected.height()));
            return new StoredFileView(fileId, inspected.mimeType(), inspected.originalName(),
                object.sizeBytes(), object.sha256(), inspected.width(), inspected.height(),
                inspected.pageCount());
        } catch (RuntimeException exception) {
            // 补偿：元数据没写成功，对象就是孤儿。立刻尝试删掉；
            // 删除也失败时只记存储键——文件名和内容可能含学生信息，不能进日志。
            compensate(storageKey);
            throw exception;
        }
    }

    private void compensate(String storageKey) {
        try {
            storage.delete(storageKey);
        } catch (IOException | RuntimeException cleanupFailure) {
            log.warn("补偿删除失败，对象将留给清理任务处理：key={}", storageKey, cleanupFailure);
        }
    }

    /**
     * 教师读取：文件必须属于该教师，并且被该教师名下的一份文档、一道题目或一页学生答卷引用。
     *
     * <p>三条引用链都要留着：文档链覆盖"还在校对中的原卷与页面"，题目链覆盖"已经确认入库的题图"，
     * 提交链覆盖"学生答卷的页面图、缩略图与旋转图"。只留前两条的话，教师一旦把区域上的裁剪引用
     * 摘掉（重新框选就会），已经确认的题图就会在题库页面变成读不出来的坏图；而提交链少一条，
     * 教师在批改页面上就会看到自己学生的答卷是一片空白。
     */
    public StoredFileDownload openForTeacher(long teacherId, long fileId) {
        return open(fileId, """
            and sf.teacher_id = :ownerId
            and (
              exists (
                select 1 from document_upload du
                left join document_page dp on dp.document_id = du.id
                left join document_region dr on dr.page_id = dp.id
                where du.teacher_id = :ownerId
                  and (du.original_file_id = sf.id or dp.page_file_id = sf.id
                       or dp.thumbnail_file_id = sf.id or dr.crop_file_id = sf.id))
              or exists (
                select 1 from question_asset qa
                join question q on q.id = qa.question_id
                where qa.file_id = sf.id and q.teacher_id = :ownerId and q.deleted_at is null)
              or exists (
                select 1 from submission_page sp
                join submission_version sv on sv.id = sp.submission_version_id
                join assignment a on a.id = sv.assignment_id
                where a.teacher_id = :ownerId
                  and (sp.page_file_id = sf.id or sp.thumbnail_file_id = sf.id
                       or sp.rotated_file_id = sf.id))
            )
            """, teacherId);
    }

    /**
     * 学生读取：只允许看自己提交里的文件。
     *
     * <p>三个条件缺一不可——{@code sf.student_id} 保证文件属于本人，
     * {@code du.student_id} 与 {@code sv.student_id} 保证它确实是自己某份提交的一部分
     * （教师试卷类文件对谁都不可读）。
     *
     * <p>提交链不能省：页面图与缩略图是服务端渲染出来的，它们身上没有 {@code document_upload}
     * 直接指向，只有 {@code submission_page} 认领。少了这条，学生在手机上看到的是自己答卷的
     * 一片空白 —— 而这恰恰是他唯一能确认"我交对了没有"的地方。
     */
    public StoredFileDownload openForStudent(long studentId, long fileId) {
        return open(fileId, """
            and sf.student_id = :ownerId
            and (
              exists (
                select 1 from document_upload du
                left join document_page dp on dp.document_id = du.id
                left join document_region dr on dr.page_id = dp.id
                where du.document_kind = 'STUDENT_SUBMISSION' and du.student_id = :ownerId
                  and (du.original_file_id = sf.id or dp.page_file_id = sf.id
                       or dp.thumbnail_file_id = sf.id or dr.crop_file_id = sf.id))
              or exists (
                select 1 from submission_page sp
                join submission_version sv on sv.id = sp.submission_version_id
                where sv.student_id = :ownerId
                  and (sp.page_file_id = sf.id or sp.thumbnail_file_id = sf.id
                       or sp.rotated_file_id = sf.id))
            )
            """, studentId);
    }

    private StoredFileDownload open(long fileId, String ownershipClause, long ownerId) {
        Optional<StoredFileRow> row = jdbc.sql("""
                select sf.id, sf.storage_key, sf.mime_type, sf.original_name, sf.size_bytes
                from stored_file sf
                where sf.id = :fileId and sf.status = 'ACTIVE' and sf.deleted_at is null
                """ + ownershipClause)
            .param("fileId", fileId)
            .param("ownerId", ownerId)
            .query((rs, rowNum) -> new StoredFileRow(
                rs.getLong("id"), rs.getString("storage_key"), rs.getString("mime_type"),
                rs.getString("original_name"), rs.getLong("size_bytes")))
            .optional();
        // 不存在的文件与不属于自己的文件返回同一个 404：区分开来等于确认"这个 id 是有效的"。
        StoredFileRow found = row.orElseThrow(() -> new DomainException(
            "FILE_NOT_FOUND", "文件不存在或无权访问", HttpStatus.NOT_FOUND));
        try {
            InputStream content = storage.open(found.storageKey());
            return new StoredFileDownload(content, found.mimeType(), found.originalName(), found.sizeBytes());
        } catch (IOException exception) {
            throw new DomainException("STORAGE_READ_FAILED", "文件内容暂时不可用",
                HttpStatus.SERVICE_UNAVAILABLE, exception);
        }
    }

    private static String extensionSuffix(String safeName) {
        int dot = safeName.lastIndexOf('.');
        return dot < 0 ? "" : safeName.substring(dot);
    }

    private record StoredFileRow(long id, String storageKey, String mimeType,
                                 String originalName, long sizeBytes) {}

    /** 落库后的文件视图，供上传接口回显。 */
    public record StoredFileView(long id, String mimeType, String originalName, long sizeBytes,
                                 String sha256, int width, int height, int pageCount) {}

    /** 带内容的下载结果；{@code content} 由调用方负责关闭。 */
    public record StoredFileDownload(InputStream content, String mimeType, String originalName, long sizeBytes) {}
}
