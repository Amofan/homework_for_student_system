package com.homework.analysis.submission;

import com.homework.analysis.assignment.AssignmentService;
import com.homework.analysis.assignment.AssignmentStatus;
import com.homework.analysis.document.ImageTransforms;
import com.homework.analysis.document.PdfPageRenderer;
import com.homework.analysis.document.StorageProperties;
import com.homework.analysis.document.StoredFileService;
import com.homework.analysis.document.UploadFileInspector;
import com.homework.analysis.grading.GradingStatus;
import com.homework.analysis.shared.error.DomainException;
import com.homework.analysis.shared.jdbc.GeneratedKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 学生提交版本：建版、页面整理、提交、锁定与退回。
 *
 * <p>这个服务要守住的几件事，写在这里以免后来者在别处再实现一遍：
 *
 * <ol>
 *   <li><b>提交是追加，不是覆盖</b>。学生点确认只新增版本行并切换 {@code is_current}，
 *       旧版本连同它的页面与审计一起冻结。学生改了什么、老师当时批的是什么，都还在。</li>
 *   <li><b>只有草稿能改</b>。{@code PROCESSING} 之后这一版只读，{@code LOCKED} 之后连新版本都
 *       建不了 —— 后者是"已经开始批改"的唯一硬闸门，退回是它唯一的口子。</li>
 *   <li><b>越权与不存在返回同一个 404</b>。版本 id 是连续数字，区分"不存在"与"不是你的"
 *       等于把别人的提交变成可枚举的信息。</li>
 *   <li><b>状态判断只在这里</b>。接口层不写 {@code if (status == X)}，一律由本服务给出的
 *       {@code editable} / {@code canStartNewVersion} 决定，避免两处判断分叉成"按钮能点但接口报错"。</li>
 * </ol>
 */
@Service
public class SubmissionVersionService {

    private static final Logger log = LoggerFactory.getLogger(SubmissionVersionService.class);

    /** 与 V9 的 document_kind 取值一致。 */
    static final String KIND_STUDENT_SUBMISSION = "STUDENT_SUBMISSION";

    /** 识别处理版本。换 OCR 模型或参数时改这里，会让已完成的文档重新排一次队。 */
    private static final int PROCESSING_VERSION = 1;

    /** 可编辑状态的 SQL 取值。由枚举生成而不是手抄，见 {@link SubmissionVersionStatus#editableNamesSql()}。 */
    private static final String EDITABLE_STATUSES = SubmissionVersionStatus.editableNamesSql();

    /**
     * 页面质量取值，与 {@code submission_page.quality_status} 的默认值和 {@code document_page} 一致。
     *
     * <p>只有 {@code BLOCKING} 直接拦下提交，{@code WARNING} 需要学生逐页确认后才放行：
     * 照片确实暗了一点但题都能看清，这种情况下不让学生交作业是拿质量检测当挡箭牌。
     */
    private static final String QUALITY_BLOCKING = "BLOCKING";

    private static final String QUALITY_WARNING = "WARNING";

    /** 允许的旋转角度，与迁移里的检查约束一致。 */
    private static final Set<Integer> ALLOWED_ROTATIONS = Set.of(0, 90, 180, 270);

    /** 与 {@code submission_version.return_reason} 的列宽一致。 */
    private static final int MAX_RETURN_REASON = 1000;

    /** 与 {@code grading_result.invalidated_reason} 的列宽一致。 */
    private static final int MAX_INVALIDATED_REASON = 500;

    /**
     * 学生自己上传的提交放进 {@code submission.status} 的取值。
     *
     * <p>沿用既有的 {@code IMPORTED}（"有答案待批改"）而不新增取值：这一列已经被学情统计、
     * 教师列表和前端筛选按现有取值读过一遍，新增一个取值意味着每一处都要跟着改，
     * 漏一处就变成"这份提交在某些页面里不存在"。
     */
    private static final String SUBMISSION_STATUS_IMPORTED = "IMPORTED";

    private final JdbcClient jdbc;
    private final AssignmentService assignments;
    private final StoredFileService files;
    private final UploadFileInspector inspector;
    private final PdfPageRenderer pdfRenderer;
    private final StorageProperties storage;

    SubmissionVersionService(JdbcClient jdbc, AssignmentService assignments, StoredFileService files,
                             UploadFileInspector inspector, PdfPageRenderer pdfRenderer,
                             StorageProperties storage) {
        this.jdbc = jdbc;
        this.assignments = assignments;
        this.files = files;
        this.inspector = inspector;
        this.pdfRenderer = pdfRenderer;
        this.storage = storage;
    }

    /** 一次上传请求里的一个文件：与 {@code MultipartFile} 解耦，服务层不依赖 Web 类型。 */
    public record UploadFile(String originalName, String contentType, byte[] content) {
    }

    /** 学生的一次页面整理结果：完整的新顺序，每个 pageId 恰好出现一次。 */
    public record PageOrder(List<Long> pageIds) {
    }

    // ---------- 建版与读取 ----------

    /**
     * 拿到当前可编辑的版本；没有就建一个。
     *
     * <p>幂等：学生刷新页面、连点两下"开始作答"都会走到这里，返回的必须是同一版草稿。
     * 幂等性靠"找现有的可编辑版本"而不是 {@code is_current} —— 草稿不是当前提交
     * （上一版已提交的才是），拿 {@code is_current} 找草稿会让每次刷新都新建一版。
     *
     * <p>唯一的例外是这份作业上已经有版本进入 {@code LOCKED}：那时学生这一侧什么都不许再做，
     * 因为批改已经开始，他改草稿或再交一版都会让老师正在批的那一份突然不是他手上那份。
     * 出口只有教师退回（见 {@link #requireGradingNotStarted}）。
     */
    @Transactional
    public SubmissionVersionView createDraft(long studentId, long assignmentId) {
        requireSubmittableAssignment(studentId, assignmentId);
        lockAssignment(assignmentId);
        // 先查批改有没有开始，再去找现成的草稿：反过来的话，学生手上还开着一版草稿时
        // 教师锁住了提交，这一版草稿照样能继续改、继续交，把老师正在批的那一份顶掉。
        requireGradingNotStarted(assignmentId, studentId);
        Optional<VersionRow> draft = findEditable(assignmentId, studentId);
        if (draft.isPresent()) {
            return detail(draft.get());
        }
        long submissionId = ensureSubmission(assignmentId, studentId);
        int versionNo = nextVersionNo(assignmentId, studentId);
        long versionId = GeneratedKeys.insert(jdbc, """
            insert into submission_version(submission_id, assignment_id, student_id,
                                           version_no, status, is_current)
            values (:submissionId, :assignmentId, :studentId, :versionNo, 'DRAFT', false)
            """, statement -> statement
            .param("submissionId", submissionId)
            .param("assignmentId", assignmentId)
            .param("studentId", studentId)
            .param("versionNo", versionNo));
        audit(versionId, assignmentId, studentId, SubmissionAuditAction.CREATED,
            SubmissionAuditAction.ActorRole.STUDENT, studentId, "第 " + versionNo + " 版");
        return detail(requireVersionForStudent(studentId, versionId));
    }

    public SubmissionVersionView.History history(long studentId, long assignmentId) {
        AssignmentRow assignment = requireSubmittableAssignment(studentId, assignmentId);
        List<SubmissionVersionView.Summary> versions = jdbc.sql("""
                select v.id, v.version_no, v.status, v.is_current, v.submitted_at, v.returned_at,
                       v.return_reason,
                       (select count(*) from submission_page p where p.submission_version_id = v.id) as page_count
                from submission_version v
                where v.assignment_id = :assignmentId and v.student_id = :studentId
                order by v.version_no desc
                """)
            .param("assignmentId", assignmentId)
            .param("studentId", studentId)
            .query(SubmissionVersionService::toSummary)
            .list();
        Long currentVersionId = versions.stream()
            .filter(SubmissionVersionView.Summary::current)
            .map(SubmissionVersionView.Summary::id)
            .findFirst()
            .orElse(null);
        return new SubmissionVersionView.History(assignmentId, currentVersionId,
            canStartNewVersion(assignment, versions), versions);
    }

    public SubmissionVersionView detailForStudent(long studentId, long versionId) {
        return detail(requireVersionForStudent(studentId, versionId));
    }

    public SubmissionVersionView detailForTeacher(long teacherId, long versionId) {
        return detail(requireVersionForTeacher(teacherId, versionId));
    }

    /**
     * 教师待办：某份作业下所有学生的提交版本。
     *
     * <p>待校对题数用子查询取而不是 join 聚合：一份版本还没有候选时（还没识别好）要显示成 0，
     * 而不是从列表里消失——教师要找的恰恰是"这份怎么还没识别完"。
     *
     * <p>只列已提交的版本：{@code DRAFT} / {@code UPLOADED} 是学生手上还没交的东西，
     * 列出来教师会以为学生已经交过了。
     *
     * <p>这条路是教师看答卷的主入口，所以归属校验走作业：不是自己名下的作业一律 404，
     * 与"作业不存在"给同一个回答。
     */
    public SubmissionVersionView.TeacherQueue teacherQueue(long teacherId, long assignmentId) {
        String title = jdbc.sql("select title from assignment where id = :id and teacher_id = :teacherId")
            .param("id", assignmentId)
            .param("teacherId", teacherId)
            .query(String.class)
            .optional()
            .orElseThrow(() -> new DomainException("ASSIGNMENT_NOT_FOUND", "作业不存在", HttpStatus.NOT_FOUND));
        List<SubmissionVersionView.TeacherQueueItem> items = jdbc.sql("""
                select v.id, v.version_no, v.status, v.is_current, v.submitted_at, v.returned_at,
                       v.return_reason, s.id as student_id, s.student_no, s.name as student_name,
                       (select count(*) from submission_page p where p.submission_version_id = v.id) as page_count,
                       (select count(*) from submission_answer_candidate c
                         where c.submission_version_id = v.id) as candidate_count,
                       (select count(*) from submission_answer_candidate c
                         where c.submission_version_id = v.id and c.review_status = 'PENDING') as pending_count
                from submission_version v
                join student s on s.id = v.student_id
                where v.assignment_id = :assignmentId and v.status not in ('DRAFT', 'UPLOADED')
                order by s.student_no, v.version_no desc
                """)
            .param("assignmentId", assignmentId)
            .query((rs, rowNum) -> new SubmissionVersionView.TeacherQueueItem(
                rs.getLong("id"), rs.getInt("version_no"), rs.getLong("student_id"),
                rs.getString("student_no"), rs.getString("student_name"),
                SubmissionVersionStatus.parse(rs.getString("status")), rs.getBoolean("is_current"),
                instant(rs, "submitted_at"), instant(rs, "returned_at"), rs.getString("return_reason"),
                rs.getInt("page_count"), rs.getInt("candidate_count"), rs.getInt("pending_count")))
            .list();
        return new SubmissionVersionView.TeacherQueue(assignmentId, title, items);
    }

    // ---------- 页面整理 ----------

    /**
     * 追加一次上传（可能包含多个文件，也可能一个 PDF 拆成多页）。
     *
     * <p>图片的页面图直接指向学生交的那份字节，不另存一份：{@code document_upload.original_file_id}
     * 已经是它了，再存一遍只是把同一份内容在存储里放两份。PDF 才需要按 DPI 渲染出页面图，
     * 那是新内容，必须存。
     *
     * <p>缩略图一律另存：它在手机上传页上要立刻显示，而页面图可能有好几 MB。
     */
    @Transactional
    public SubmissionVersionView addPages(long studentId, long versionId, List<UploadFile> uploads) {
        VersionRow version = requireEditableVersion(studentId, versionId);
        if (uploads.isEmpty()) {
            throw new DomainException("SUBMISSION_FILE_REQUIRED", "请至少选择一个文件", HttpStatus.BAD_REQUEST);
        }
        long incomingBytes = uploads.stream().mapToLong(upload -> upload.content().length).sum();
        if (storedBytes(versionId) + incomingBytes > storage.maxSubmissionBytes()) {
            throw new DomainException("SUBMISSION_TOO_LARGE",
                "整份答卷不能超过 " + storage.maxSubmissionBytes() + " 字节", HttpStatus.BAD_REQUEST);
        }
        long teacherId = teacherIdOf(version.assignmentId());
        int nextPageNo = pageRows(versionId).size() + 1;
        int addedPages = 0;
        for (UploadFile upload : uploads) {
            UploadFileInspector.InspectedFile inspected =
                inspector.inspect(upload.originalName(), upload.contentType(), upload.content());
            int pageCount = inspected.isPdf() ? inspected.pageCount() : 1;
            if (nextPageNo - 1 + pageCount > storage.maxPages()) {
                throw new DomainException("SUBMISSION_TOO_MANY_PAGES",
                    "一份答卷最多 " + storage.maxPages() + " 页", HttpStatus.BAD_REQUEST);
            }
            StoredFileService.StoredFileView original = files.storeStudentFile(
                studentId, inspected.originalName(), inspected.mimeType(), upload.content());
            long documentId = GeneratedKeys.insert(jdbc, """
                insert into document_upload(document_kind, teacher_id, assignment_id, student_id,
                                            submission_version_id, original_file_id, status)
                values (:kind, :teacherId, :assignmentId, :studentId, :versionId, :fileId, 'PENDING')
                """, statement -> statement
                .param("kind", KIND_STUDENT_SUBMISSION)
                .param("teacherId", teacherId)
                .param("assignmentId", version.assignmentId())
                .param("studentId", studentId)
                .param("versionId", versionId)
                .param("fileId", original.id()));
            List<PdfPageRenderer.PageImage> rendered =
                inspected.isPdf() ? renderPdf(upload.content()) : List.of();
            for (int pageNoInFile = 1; pageNoInFile <= pageCount; pageNoInFile++) {
                byte[] pageBytes = inspected.isPdf()
                    ? rendered.get(pageNoInFile - 1).png()
                    : upload.content();
                StoredFileService.StoredFileView pageFile = inspected.isPdf()
                    ? files.storeStudentFile(studentId,
                        pageName(inspected.originalName(), pageNoInFile), "image/png", pageBytes)
                    : original;
                StoredFileService.StoredFileView thumbnail = files.storeStudentFile(studentId,
                    "thumb-" + pageName(inspected.originalName(), pageNoInFile), "image/png",
                    ImageTransforms.thumbnail(pageBytes));
                int width = inspected.isPdf() ? rendered.get(pageNoInFile - 1).width() : inspected.width();
                int height = inspected.isPdf() ? rendered.get(pageNoInFile - 1).height() : inspected.height();
                jdbc.sql("""
                        insert into submission_page(submission_version_id, page_no, document_id,
                                                    document_page_no, page_file_id, rotated_file_id,
                                                    thumbnail_file_id, rotation_degrees, width, height)
                        values (:versionId, :pageNo, :documentId, :documentPageNo, :pageFileId,
                                :pageFileId, :thumbnailId, 0, :width, :height)
                        """)
                    .param("versionId", versionId)
                    .param("pageNo", nextPageNo)
                    .param("documentId", documentId)
                    .param("documentPageNo", pageNoInFile)
                    .param("pageFileId", pageFile.id())
                    .param("thumbnailId", thumbnail.id())
                    .param("width", width > 0 ? width : null)
                    .param("height", height > 0 ? height : null)
                    .update();
                nextPageNo++;
                addedPages++;
            }
        }
        // 有页面就是 UPLOADED。只从 DRAFT 往前推一格，别把已经提交的版本改回去。
        jdbc.sql("""
                update submission_version set status = 'UPLOADED', updated_at = current_timestamp(3)
                where id = :id and status = 'DRAFT'
                """)
            .param("id", versionId)
            .update();
        audit(versionId, version.assignmentId(), studentId, SubmissionAuditAction.PAGE_ADDED,
            SubmissionAuditAction.ActorRole.STUDENT, studentId, "新增 " + addedPages + " 页");
        return detail(requireVersionForStudent(studentId, versionId));
    }

    /**
     * 重排页面。
     *
     * <p>要求传来的列表恰好是当前全部页面、每个一次：只传"动过的那几页"会让没提到的页面
     * 落在一个说不清的位置上，而顺序是学生唯一能表达"这是我第几页"的手段。
     */
    @Transactional
    public SubmissionVersionView reorderPages(long studentId, long versionId, List<Long> pageIds) {
        VersionRow version = requireEditableVersion(studentId, versionId);
        List<PageRow> pages = pageRows(versionId);
        Set<Long> existing = pages.stream().map(PageRow::id)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> requested = new LinkedHashSet<>(pageIds);
        if (pageIds.size() != pages.size() || requested.size() != pageIds.size() || !requested.equals(existing)) {
            throw new DomainException("SUBMISSION_PAGE_ORDER_INVALID",
                "页面顺序必须恰好包含当前全部页面，每个一次", HttpStatus.BAD_REQUEST);
        }
        // 两段式：先整体挪到负数区间，再写目标页码。逐个写目标值会在中途撞 uk_submission_page_no
        // —— 第 1、2 页互换时，写第一行 2 的瞬间第二行还是 2。page_no 上没有取值范围约束，
        // 负数只是中转站。
        jdbc.sql("update submission_page set page_no = -page_no where submission_version_id = :versionId")
            .param("versionId", versionId)
            .update();
        for (int index = 0; index < pageIds.size(); index++) {
            jdbc.sql("""
                    update submission_page set page_no = :pageNo, updated_at = current_timestamp(3)
                    where id = :id and submission_version_id = :versionId
                    """)
                .param("pageNo", index + 1)
                .param("id", pageIds.get(index))
                .param("versionId", versionId)
                .update();
        }
        audit(versionId, version.assignmentId(), studentId, SubmissionAuditAction.PAGE_REORDERED,
            SubmissionAuditAction.ActorRole.STUDENT, studentId, "重新排序 " + pageIds.size() + " 页");
        return detail(requireVersionForStudent(studentId, versionId));
    }

    /**
     * 旋转某一页。
     *
     * <p>{@code degrees} 是目标角度而不是增量：连点两次 90 得到的是 90 度，不是 180 度。
     * 每次都从页面原图重新转，所以顺序与次数都不影响结果。
     *
     * <p>旋转产生的是新文件，页面原图不动 —— 学生交的原样要一直留着，
     * 否则教师质疑某道题被判错时，能拿出来的已经不是学生交的那一张了。
     */
    @Transactional
    public SubmissionVersionView rotatePage(long studentId, long versionId, long pageId, int degrees) {
        VersionRow version = requireEditableVersion(studentId, versionId);
        if (!ALLOWED_ROTATIONS.contains(degrees)) {
            throw new DomainException("SUBMISSION_ROTATION_INVALID",
                "只支持 0、90、180、270 度", HttpStatus.BAD_REQUEST);
        }
        PageRow page = requirePage(versionId, pageId);
        // 转多少度都要先解一次原始页面图：宽高必须由这张原图算出来。拿页面上记着的宽高再翻一次
        // 是错的 —— 对一张已经转过 90 度的页面，那对宽高早就互换过了，再换一次等于换回去，
        // 连点两下 90 度尺寸就回到原样，后面按区域裁剪会照着一个横过来的坐标去截一张竖着的图。
        ImageTransforms.RotatedImage rotated =
            ImageTransforms.rotate(readStudentFile(studentId, page.pageFileId()), degrees);
        long rotatedFileId;
        Long thumbnailFileId;
        if (degrees == 0) {
            // 转回原样：指回原始页面图，不再存一份一模一样的副本。
            rotatedFileId = page.pageFileId();
            // 缩略图要跟着当前角度走。从 90 度转回原样时页面上挂着的还是那张侧躺的缩略图，
            // 继续沿用的话，学生在页列表里看到的这一页仍是横的，和点开之后看到的不一致。
            // 只有这一页本来就停在 0 度（那张缩略图就是按原图生成的）才直接沿用。
            thumbnailFileId = page.rotationDegrees() == 0 && page.thumbnailFileId() != null
                ? page.thumbnailFileId()
                : storeThumbnail(studentId, page.pageFileId(), page.pageNo());
        } else {
            rotatedFileId = files.storeStudentFile(studentId,
                "page-" + page.pageNo() + "-r" + degrees + ".png", "image/png", rotated.content()).id();
            thumbnailFileId = files.storeStudentFile(studentId,
                "thumb-page-" + page.pageNo() + "-r" + degrees + ".png", "image/png",
                ImageTransforms.thumbnail(rotated.content())).id();
        }
        jdbc.sql("""
                update submission_page set rotation_degrees = :degrees, rotated_file_id = :rotatedFileId,
                    thumbnail_file_id = :thumbnailFileId, width = :width, height = :height,
                    updated_at = current_timestamp(3)
                where id = :id
                """)
            .param("degrees", degrees)
            .param("rotatedFileId", rotatedFileId)
            .param("thumbnailFileId", thumbnailFileId)
            .param("width", rotated.width())
            .param("height", rotated.height())
            .param("id", pageId)
            .update();
        audit(versionId, version.assignmentId(), studentId, SubmissionAuditAction.PAGE_ROTATED,
            SubmissionAuditAction.ActorRole.STUDENT, studentId, "第 " + page.pageNo() + " 页旋转 " + degrees + " 度");
        return detail(requireVersionForStudent(studentId, versionId));
    }

    /**
     * 删掉一页并重新编号。
     *
     * <p>删完不留空号：页码是学生看到的顺序，{@code 1,2,4} 里那个 3 会让人以为丢了一页。
     * 一并留下的 {@code document_upload} 不用清理 —— 提交时按"这个版本还剩哪些页面"建识别任务，
     * 那份上传已经没有页面，自然不会被识别。
     */
    @Transactional
    public SubmissionVersionView deletePage(long studentId, long versionId, long pageId) {
        VersionRow version = requireEditableVersion(studentId, versionId);
        PageRow page = requirePage(versionId, pageId);
        jdbc.sql("delete from submission_page where id = :id and submission_version_id = :versionId")
            .param("id", pageId)
            .param("versionId", versionId)
            .update();
        jdbc.sql("""
                update submission_version set status = 'DRAFT', updated_at = current_timestamp(3)
                where id = :id and status = 'UPLOADED'
                  and not exists (select 1 from submission_page where submission_version_id = :id)
                """)
            .param("id", versionId)
            .update();
        renumberPages(versionId);
        audit(versionId, version.assignmentId(), studentId, SubmissionAuditAction.PAGE_REMOVED,
            SubmissionAuditAction.ActorRole.STUDENT, studentId, "删除第 " + page.pageNo() + " 页");
        return detail(requireVersionForStudent(studentId, versionId));
    }

    /** 删除后把剩下的页码密集重排。升序更新是安全的：目标位置总不比原位置大。 */
    private void renumberPages(long versionId) {
        List<Long> pageIds = jdbc.sql("""
                select id from submission_page where submission_version_id = :versionId order by page_no
                """)
            .param("versionId", versionId)
            .query(Long.class)
            .list();
        for (int index = 0; index < pageIds.size(); index++) {
            jdbc.sql("update submission_page set page_no = :pageNo where id = :id")
                .param("pageNo", index + 1)
                .param("id", pageIds.get(index))
                .update();
        }
    }

    // ---------- 提交、锁定与退回 ----------

    /**
     * 学生确认提交。
     *
     * <p>这是这一版从"学生可改"变成只读的那一步，也是唯一切换 {@code is_current} 的动作：
     * 旧版置 {@code SUPERSEDED}、新版置 {@code PROCESSING} 在同一事务里完成，
     * 所以任何时刻"当前提交"要么恰好一份，要么一份都没有（还没交过，或已被退回）。
     *
     * <p>重复提交按重试处理而不是报错：客户端没收到响应就会再发一次，
     * 学生刚点的那一下不该看起来像失败。
     *
     * <p>{@code acknowledgedPageIds} 是学生逐页确认过的页面，只对质量检测给出
     * {@code WARNING} 的页面有意义。{@code BLOCKING} 没有确认这条路 —— 那种照片交上去，
     * 教师看到的就是一片糊，整个提交都得作废重来。
     */
    @Transactional
    public SubmissionVersionView submit(long studentId, long versionId, List<Long> acknowledgedPageIds) {
        VersionRow version = requireVersionForStudent(studentId, versionId);
        if (alreadySubmitted(version)) {
            return detail(version);
        }
        // 交作业时再确认一次作业还开着：学生可能昨天打开页面，今天才点提交。
        requireSubmittableAssignment(studentId, version.assignmentId());
        requireEditableStatus(version);
        requireGradingNotStarted(version.assignmentId(), studentId);
        lockAssignment(version.assignmentId());
        // 锁到作业行之后重读：拿到锁之前可能已经被另一个请求提交掉了（双开页面、连点两下），
        // 也可能教师刚点了"开始批改"。两件事都只认重读之后的状态。
        VersionRow fresh = requireVersionForStudent(studentId, versionId);
        if (alreadySubmitted(fresh)) {
            return detail(fresh);
        }
        requireEditableStatus(fresh);
        requireGradingNotStarted(fresh.assignmentId(), studentId);
        List<PageRow> pages = pageRows(versionId);
        if (pages.isEmpty()) {
            throw new DomainException("SUBMISSION_NO_PAGE", "还没有上传任何页面", HttpStatus.BAD_REQUEST);
        }
        requireUsablePages(pages, acknowledgedPageIds);
        requirePageFilesActive(versionId, pages.size());
        supersedePriorCurrent(fresh);
        jdbc.sql("""
                update submission_version
                set status = 'PROCESSING', is_current = true, submitted_at = current_timestamp(3),
                    updated_at = current_timestamp(3)
                where id = :id
                """)
            .param("id", versionId)
            .update();
        audit(versionId, fresh.assignmentId(), studentId, SubmissionAuditAction.SUBMITTED,
            SubmissionAuditAction.ActorRole.STUDENT, studentId,
            "第 " + fresh.versionNo() + " 版，共 " + pages.size() + " 页");
        enqueueOcrTasks(versionId);
        return detail(requireVersionForStudent(studentId, versionId));
    }

    /**
     * 开始批改：把当前提交钉住。
     *
     * <p>{@code LOCKED} 是学生动不了的唯一硬闸门，所以两件事都要成立：这一版得是当前提交
     * （否则锁的是老师不会再批的一版，学生照样能重交），并且答案已经被教师确认过
     * （否则锁的是一份没人看过的作答）。
     *
     * <p>已经是 {@code LOCKED} 时直接返回：连点两下"开始批改"不该报错。
     */
    @Transactional
    public SubmissionVersionView lockForTeacher(long teacherId, long versionId) {
        VersionRow version = requireVersionForTeacher(teacherId, versionId);
        if (version.status() == SubmissionVersionStatus.LOCKED) {
            return detail(version);
        }
        if (!version.current()) {
            throw new DomainException("SUBMISSION_NOT_CURRENT",
                "这一版已经被新版本取代，请对当前提交开始批改", HttpStatus.CONFLICT);
        }
        if (version.status() != SubmissionVersionStatus.CONFIRMED) {
            throw new DomainException("SUBMISSION_NOT_CONFIRMED",
                "答案还没有确认，不能开始批改", HttpStatus.CONFLICT);
        }
        jdbc.sql("""
                update submission_version
                set status = 'LOCKED', locked_at = current_timestamp(3), updated_at = current_timestamp(3)
                where id = :id
                """)
            .param("id", versionId)
            .update();
        audit(versionId, version.assignmentId(), version.studentId(), SubmissionAuditAction.LOCKED,
            SubmissionAuditAction.ActorRole.TEACHER, teacherId, "开始批改第 " + version.versionNo() + " 版");
        return detail(requireVersionForTeacher(teacherId, versionId));
    }

    // ---------- 校对链路的状态迁移 ----------
    //
    // 这两个迁移写在这里而不是各自的业务服务里，与 is_current 的维护出于同一条理由：
    // 版本状态是这份提交在整条链路里的位置，散在几处改，迟早会出现"某处以为还在识别、
    // 某处已经在批改"的分叉。

    /**
     * 识别结果已落成答案候选：这一版转到"等教师校对"。
     *
     * <p>只从 {@code PROCESSING} 往前挪，其它状态一律不动：重复物化一次时状态已经是
     * {@code NEEDS_REVIEW}，再写一遍会把它从"教师正在校对"打回"刚识别完"，
     * 而界面上正开着的那一版会被当成过时。
     */
    void markAwaitingReview(VersionRef version, int candidateCount) {
        int updated = jdbc.sql("""
                update submission_version
                set status = 'NEEDS_REVIEW', updated_at = current_timestamp(3)
                where id = :id and status = 'PROCESSING'
                """)
            .param("id", version.versionId())
            .update();
        if (updated == 0) {
            return;
        }
        audit(version.versionId(), version.assignmentId(), version.studentId(),
            SubmissionAuditAction.OCR_STATE_CHANGED, SubmissionAuditAction.ActorRole.SYSTEM, null,
            "识别完成，共 " + candidateCount + " 道题的作答待校对");
    }

    /**
     * 教师改了识别文字或题目映射。
     *
     * <p>{@code detail} 里**只写改了哪些字段**，不写改前改后的内容：那是一道题的学生作答，
     * 而审计行会被很多眼睛看到（排查问题、申诉调阅），内容由候选行本身保存，
     * 重复一份在这里既没有必要，也让审计表变成了另一个答案副本。
     */
    void auditAnswerCorrection(VersionRef version, long teacherId, String detail) {
        audit(version.versionId(), version.assignmentId(), version.studentId(),
            SubmissionAuditAction.OCR_TEXT_CORRECTED, SubmissionAuditAction.ActorRole.TEACHER,
            teacherId, detail);
    }

    /**
     * 教师确认答案入库：这一版转 {@code CONFIRMED}，库里那套答案的来源挪到它。
     *
     * <p>两件事必须一起做。只改状态不搬指针，{@code student_answer} 里那套答案仍然挂在上一版上，
     * 而批改会按这套答案算分——学生改了、老师也确认了，分数却还是旧作答的。
     *
     * <p>{@code where} 带着 {@code is_current = true}：这一版如果在这几步之间被学生的重交取代了，
     * 影响行数为 0，调用方据此报冲突。放它过去会让一份学生已经不要的作答变成正式答案。
     */
    void markAnswersConfirmed(VersionRef version, long teacherId) {
        int updated = jdbc.sql("""
                update submission_version
                set status = 'CONFIRMED', updated_at = current_timestamp(3)
                where id = :id and is_current = true and status in ('PROCESSING', 'NEEDS_REVIEW')
                """)
            .param("id", version.versionId())
            .update();
        if (updated == 0) {
            throw new DomainException("SUBMISSION_NOT_CURRENT",
                "这一版已经不是当前提交了，请对最新的那一版确认答案", HttpStatus.CONFLICT);
        }
        jdbc.sql("""
                update submission
                set submission_version_id = :versionId, updated_at = current_timestamp(3)
                where id = :submissionId
                """)
            .param("versionId", version.versionId())
            .param("submissionId", version.submissionId())
            .update();
        audit(version.versionId(), version.assignmentId(), version.studentId(),
            SubmissionAuditAction.ANSWERS_CONFIRMED, SubmissionAuditAction.ActorRole.TEACHER, teacherId,
            "第 " + version.versionNo() + " 版的作答已确认入库");
    }

    /**
     * 教师退回，学生可以重新提交。
     *
     * <p>退回不只是改状态：库里那套答案、基于它的 AI 建议与未确认评分都必须失效，
     * 否则学生看到的是"老师让我改"，而系统里成绩已经算好了。失效用标记而不是删除 ——
     * 教师可能已经看过那些建议，删掉等于把发生过的事抹掉。
     *
     * <p>唯一不能作废的是教师已确认的评分：那是人做出的判断。这时直接拒绝退回
     * （{@code SUBMISSION_ALREADY_FINALIZED}），而不是把评分静默作废 ——
     * 退回是让学生改错，不是让系统把老师的工作丢掉。
     */
    @Transactional
    public SubmissionVersionView returnToStudent(long teacherId, long versionId, String reason) {
        String cleanReason = reason == null ? "" : reason.trim();
        if (cleanReason.isEmpty()) {
            throw new DomainException("SUBMISSION_RETURN_REASON_REQUIRED",
                "退回必须说明原因", HttpStatus.BAD_REQUEST);
        }
        if (cleanReason.length() > MAX_RETURN_REASON) {
            // 库里的列装不下就要给 400，而不是让插入失败变成 500：这是输入长度问题，
            // 教师改短一点就能过。
            throw new DomainException("SUBMISSION_RETURN_REASON_TOO_LONG",
                "退回原因不能超过 " + MAX_RETURN_REASON + " 个字", HttpStatus.BAD_REQUEST);
        }
        VersionRow version = requireVersionForTeacher(teacherId, versionId);
        if (!version.status().submitted()) {
            throw new DomainException("SUBMISSION_NOT_SUBMITTED",
                "这一版还没有提交，不能退回", HttpStatus.CONFLICT);
        }
        if (version.status() == SubmissionVersionStatus.RETURNED) {
            throw new DomainException("SUBMISSION_ALREADY_RETURNED",
                "这一版已经退回过了", HttpStatus.CONFLICT);
        }
        if (version.status() == SubmissionVersionStatus.SUPERSEDED) {
            throw new DomainException("SUBMISSION_NOT_CURRENT",
                "这一版已经被新版本取代，不能退回", HttpStatus.CONFLICT);
        }
        lockAssignment(version.assignmentId());
        invalidateLiveResults(version, cleanReason);
        jdbc.sql("""
                update submission_version
                set status = 'RETURNED', is_current = false, returned_at = current_timestamp(3),
                    return_reason = :reason, updated_at = current_timestamp(3)
                where id = :id
                """)
            .param("reason", cleanReason)
            .param("id", versionId)
            .update();
        audit(versionId, version.assignmentId(), version.studentId(), SubmissionAuditAction.RETURNED,
            SubmissionAuditAction.ActorRole.TEACHER, teacherId, cleanReason);
        return detail(requireVersionForTeacher(teacherId, versionId));
    }

    /** 已经提交成功了的样子。用于把重复提交当成重试，而不是"已经提交过，不许再交"。 */
    private static boolean alreadySubmitted(VersionRow version) {
        return version.status() == SubmissionVersionStatus.PROCESSING && version.current();
    }

    private static void requireEditableStatus(VersionRow version) {
        if (!version.status().editable()) {
            throw new DomainException("SUBMISSION_NOT_EDITABLE",
                "这一版已经提交，不能再修改", HttpStatus.CONFLICT);
        }
    }

    /**
     * 批改有没有开始：这份作业上只要有一版 {@code LOCKED}，学生这一侧就什么都不许再做。
     *
     * <p>查的是"这个学生在这份作业上的全部版本"而不是被锁的那一版自己。按被锁的那一版判断，
     * 挡住的只有"交一版新的"，学生手上那版还没提交的草稿照样能改、能交 —— 而它一交上来，
     * 老师正在批的那一份就被标成 {@code SUPERSEDED}，评分对应着一份学生已经不要的作答。
     *
     * <p>也正因为如此，{@code LOCKED} 之后每一个学生写入口都要过这道门：建版、传页、排序、
     * 旋转、删除、提交。漏掉任何一个，锁就只是把"当前提交"这四个字锁住了。
     *
     * <p>出口只有一个：教师退回把版本置为 {@code RETURNED}，这道门自然打开。
     */
    private void requireGradingNotStarted(long assignmentId, long studentId) {
        Integer locked = jdbc.sql("""
                select count(*) from submission_version
                where assignment_id = :assignmentId and student_id = :studentId and status = 'LOCKED'
                """)
            .param("assignmentId", assignmentId)
            .param("studentId", studentId)
            .query(Integer.class)
            .single();
        if (locked != null && locked > 0) {
            throw new DomainException("SUBMISSION_LOCKED",
                "这一版已经开始批改，不能再修改或提交；要改请联系老师退回", HttpStatus.CONFLICT);
        }
    }

    /** 页面的质量门槛：{@code BLOCKING} 拦下，{@code WARNING} 要逐页确认。 */
    private static void requireUsablePages(List<PageRow> pages, List<Long> acknowledgedPageIds) {
        List<Integer> blocking = pages.stream()
            .filter(page -> QUALITY_BLOCKING.equals(page.qualityStatus()))
            .map(PageRow::pageNo)
            .toList();
        if (!blocking.isEmpty()) {
            throw new DomainException("SUBMISSION_QUALITY_BLOCKING",
                "第 " + joinPageNumbers(blocking) + " 页照片不合格，请重新拍摄后再提交",
                HttpStatus.UNPROCESSABLE_ENTITY);
        }
        Set<Long> acknowledged = new HashSet<>(
            acknowledgedPageIds == null ? List.of() : acknowledgedPageIds);
        List<Integer> warnings = pages.stream()
            .filter(page -> QUALITY_WARNING.equals(page.qualityStatus()))
            .filter(page -> !acknowledged.contains(page.id()))
            .map(PageRow::pageNo)
            .toList();
        if (!warnings.isEmpty()) {
            throw new DomainException("SUBMISSION_QUALITY_WARNING",
                "第 " + joinPageNumbers(warnings) + " 页照片可能不清楚，请确认后再提交",
                HttpStatus.CONFLICT);
        }
    }

    private static String joinPageNumbers(List<Integer> pageNumbers) {
        return pageNumbers.stream().map(String::valueOf).collect(Collectors.joining("、"));
    }

    /**
     * 页面图还在不在。
     *
     * <p>草稿可以放很久，而对象存储的保留策略不会等学生：文件被清理之后页面行仍然在，
     * 提交上去只会得到一份教师打不开的答卷。所以提交前数一遍，缺了就让客户端重新上传。
     */
    private void requirePageFilesActive(long versionId, int pageCount) {
        int active = jdbc.sql("""
                select count(*)
                from submission_page sp
                join stored_file sf on sf.id = sp.page_file_id
                where sp.submission_version_id = :versionId
                  and sf.status = 'ACTIVE' and sf.deleted_at is null and sf.purged_at is null
                """)
            .param("versionId", versionId)
            .query(Integer.class)
            .single();
        if (active < pageCount) {
            throw new DomainException("SUBMISSION_PAGE_FILE_MISSING",
                "有页面文件已失效，请重新上传后再提交", HttpStatus.CONFLICT);
        }
    }

    /**
     * 把上一版当前提交置为 {@code SUPERSEDED}。
     *
     * <p>只动"还是当前提交"的那一行，且不碰 {@code RETURNED}：退回是终态，
     * 把退回原因改写成"已被取代"等于把学生唯一要看的提示删掉。
     *
     * <p>调用方必须已经锁住作业行，否则两个并发提交会同时认为自己是唯一的新版本。
     */
    private void supersedePriorCurrent(VersionRow version) {
        Optional<VersionRow> prior = findCurrent(version.assignmentId(), version.studentId())
            .filter(current -> current.id() != version.id())
            .filter(current -> current.status() != SubmissionVersionStatus.RETURNED);
        if (prior.isEmpty()) {
            return;
        }
        VersionRow previous = prior.get();
        jdbc.sql("""
                update submission_version
                set status = 'SUPERSEDED', is_current = false, superseded_at = current_timestamp(3),
                    updated_at = current_timestamp(3)
                where id = :id
                """)
            .param("id", previous.id())
            .update();
        audit(previous.id(), version.assignmentId(), version.studentId(),
            SubmissionAuditAction.SUPERSEDED, SubmissionAuditAction.ActorRole.SYSTEM, null,
            "被第 " + version.versionNo() + " 版取代");
    }

    /**
     * 给这个版本剩下的每一份上传建识别任务。
     *
     * <p>按"还有页面的文档"取数而不是按上传记录：学生删掉的那几页不该再送去识别 ——
     * 他删了就是不要了，识别出来反而会出现在教师的校对列表里。
     *
     * <p>任务落在 {@code document_upload.teacher_id} 上（学生上传时取的是班级教师），
     * 所以教师侧的识别入口同样能处理学生答卷，不需要另一条驱动路径。
     */
    private void enqueueOcrTasks(long versionId) {
        int created = jdbc.sql("""
                insert into ocr_task(document_id, document_kind, storage_sha256, status, processing_version)
                select distinct sp.document_id, :kind, sf.sha256, 'PENDING', :processingVersion
                from submission_page sp
                join document_upload du on du.id = sp.document_id
                join stored_file sf on sf.id = du.original_file_id
                where sp.submission_version_id = :versionId
                  and not exists (
                    select 1 from ocr_task t
                    where t.document_id = sp.document_id and t.processing_version = :processingVersion)
                """)
            .param("kind", KIND_STUDENT_SUBMISSION)
            .param("processingVersion", PROCESSING_VERSION)
            .param("versionId", versionId)
            .update();
        log.info("提交已入队识别：versionId={} 新建任务={}", versionId, created);
    }

    /**
     * 把这一版里识别失败的任务放回队列。
     *
     * <p>为什么需要它：答卷是学生交上来的东西，教师没法像整卷导入那样"重新上传一份"。
     * 识别失败（服务不可用、引擎报错）如果不给一条重试的路，这份答卷就永远停在
     * {@code FAILED} 上——学生等到的是"老师没批"，而老师看到的是一个点不动的页面。
     *
     * <p>{@code attempt_count} 归零：这是人主动发起的重试，不是自动退避的一轮，
     * 让它继续背着上一轮的计数会让重试在两次之后又被判成"超限"。
     */
    void resetFailedOcrTasks(long versionId) {
        // 先清文档上的失败标记，再放开任务：反过来的话，中间失败会留下一个"任务可跑但文档仍标失败"
        // 的状态，而界面读的是文档状态。
        jdbc.sql("""
                update document_upload
                set status = 'PENDING', failure_reason_code = null, updated_at = current_timestamp(3)
                where failure_reason_code is not null
                  and id in (select document_id from submission_page where submission_version_id = :versionId)
                """)
            .param("versionId", versionId)
            .update();
        int reset = jdbc.sql("""
                update ocr_task
                set status = 'PENDING', failure_code = null, attempt_count = 0,
                    next_attempt_at = null, finished_at = null, updated_at = current_timestamp(3)
                where status = 'FAILED'
                  and document_id in (select document_id from submission_page where submission_version_id = :versionId)
                """)
            .param("versionId", versionId)
            .update();
        if (reset > 0) {
            log.info("答卷的识别任务已放回队列：versionId={} 任务数={}", versionId, reset);
        }
    }

    /**
     * 退回时让基于这一版作答的评分与建议失效。
     *
     * <p>只在这一版的作答确实还是库里那套答案时才动（{@code submission.submission_version_id}
     * 指向它）：学生如果已经重交过，库里那套答案属于新版，动那些评分等于替另一版作答作废评分。
     *
     * <p>已确认的教师评分不能被作废，这时拒绝退回。注意判断同样只对"库里那套答案来自这一版"
     * 成立：学生重交后，上一版的已确认评分与本版无关，退回它不该被拦。
     */
    private void invalidateLiveResults(VersionRow version, String reason) {
        Long liveVersionId = liveAnswerVersionId(version.submissionId());
        if (liveVersionId == null || liveVersionId.longValue() != version.id()) {
            return;
        }
        if (hasConfirmedResult(version.submissionId())) {
            throw new DomainException("SUBMISSION_ALREADY_FINALIZED",
                "这一版的作答已经有确认过的评分，退回会让评分失去依据；请先处理这些评分",
                HttpStatus.CONFLICT);
        }
        // 标记而不是删除：教师可能已经看过这些建议，删掉等于把发生过的事抹掉。
        // 理由要截断，退回原因本身（最多 1000 字）比 invalidated_reason 的列宽更长。
        String invalidatedReason = truncate("提交被退回：" + reason, MAX_INVALIDATED_REASON);
        // ai_grading_task 不加状态过滤：它将来会增加状态（排队中、重试中……），
        // 漏掉一种就会留下一条按旧作答生成的建议。
        int tasks = jdbc.sql("""
                update ai_grading_task
                set invalidated_at = current_timestamp(3), invalidated_reason = :reason,
                    updated_at = current_timestamp(3)
                where invalidated_at is null
                  and answer_id in (select id from student_answer where submission_id = :submissionId)
                """)
            .param("reason", invalidatedReason)
            .param("submissionId", version.submissionId())
            .update();
        int results = jdbc.sql("""
                update grading_result
                set invalidated_at = current_timestamp(3), invalidated_reason = :reason,
                    updated_at = current_timestamp(3)
                where invalidated_at is null
                  and status = :pendingReview
                  and answer_id in (select id from student_answer where submission_id = :submissionId)
                """)
            .param("reason", invalidatedReason)
            .param("pendingReview", GradingStatus.PENDING_REVIEW.name())
            .param("submissionId", version.submissionId())
            .update();
        log.info("退回作废未确认评分：versionId={} 评分={} AI 任务={}", version.id(), results, tasks);
        audit(version.id(), version.assignmentId(), version.studentId(),
            SubmissionAuditAction.OCR_STATE_CHANGED, SubmissionAuditAction.ActorRole.SYSTEM, null,
            "退回作废未确认评分 " + results + " 条、AI 建议 " + tasks + " 条");
    }

    private boolean hasConfirmedResult(long submissionId) {
        Integer confirmed = jdbc.sql("""
                select count(*) from grading_result
                where status = :confirmed
                  and answer_id in (select id from student_answer where submission_id = :submissionId)
                """)
            .param("confirmed", GradingStatus.CONFIRMED.name())
            .param("submissionId", submissionId)
            .query(Integer.class)
            .single();
        return confirmed != null && confirmed > 0;
    }

    /** 库里那套答案来自哪一版；V3 时代导入的提交没有版本，返回 {@code null}。 */
    private Long liveAnswerVersionId(long submissionId) {
        return jdbc.sql("select submission_version_id from submission where id = :id")
            .param("id", submissionId)
            .query(Long.class)
            .optional()
            .orElse(null);
    }

    private static String truncate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    // ---------- 内部读取与投影 ----------

    private record AssignmentRow(long id, long teacherId, String status, Instant publishedAt) {
    }

    private record VersionRow(long id, long submissionId, long assignmentId, long studentId, int versionNo,
                              SubmissionVersionStatus status, boolean current, Instant submittedAt,
                              Instant lockedAt, Instant returnedAt, String returnReason, Instant createdAt) {
    }

    /**
     * 一次归属校验的结果，供同包内的校对链路复用。
     *
     * <p>为什么要有这个类型而不是让各服务自己查：教师能看哪一版，取决于"这一版挂在我名下的作业上吗"。
     * 这条规则写两遍，迟早有一遍写成"按版本 id 放行"——而版本 id 是连续数字，
     * 那样等于让任意教师翻遍全班的答卷。
     */
    record VersionRef(long versionId, long submissionId, long assignmentId, long studentId, int versionNo,
                      SubmissionVersionStatus status, boolean current) {

        /**
         * 这一版能不能进答案校对链路；不能时抛出说明原因的异常。
         *
         * <p>写在 {@code VersionRef} 上而不是各服务里：跑识别、改候选、确认入库三处都要问同一个问题，
         * 而它们的回答必须完全一致——哪一处写成"按版本 id 放行"，哪一处就是"任意教师能翻遍全班答卷"。
         *
         * <p>四种拒绝各有各的处理动作：没提交（去催学生）、已被取代或退回（去看最新那一版）、
         * 答案已入库（只能退回重交）、识别失败（学生得重交）。所以四个码分开，
         * 而不是笼统一个"状态不对"。
         */
        void requireAnswerReviewable() {
            if (!status.submitted()) {
                throw new DomainException("SUBMISSION_NOT_SUBMITTED",
                    "这一版还没有提交，无法校对", HttpStatus.CONFLICT);
            }
            if (status.terminal() || !current) {
                throw new DomainException("SUBMISSION_NOT_CURRENT",
                    "这一版已经不是当前提交，请校对最新的一版", HttpStatus.CONFLICT);
            }
            if (status.answersConfirmed()) {
                throw new DomainException("SUBMISSION_ANSWERS_CONFIRMED",
                    "答案已经确认入库，不能再修改", HttpStatus.CONFLICT);
            }
            if (!status.answersReviewable()) {
                // 剩下的是 FAILED：这一版本身没成，改候选改不出任何去处。
                throw new DomainException("SUBMISSION_OCR_FAILED",
                    "这一版的识别没有成功，请让学生重新提交", HttpStatus.CONFLICT);
            }
        }
    }

    private record PageRow(long id, int pageNo, long documentId, int documentPageNo, String fileName,
                           long pageFileId, Long thumbnailFileId, long rotatedFileId, int rotationDegrees,
                           String qualityStatus, Integer width, Integer height) {
    }

    private static final String VERSION_SELECT = """
        select id, submission_id, assignment_id, student_id, version_no, status, is_current,
               submitted_at, locked_at, returned_at, return_reason, created_at
        from submission_version
        """;

    /**
     * 页面列清单。多处查询共用一份：列名写错一次就会在某一处安静地偏移，共用就不会。
     *
     * <p>文件名取的是这次上传的原始文件（{@code document_upload.original_file_id}），不是
     * {@code page_file_id} 指向的那一份：旋转会给页面换一张派生图（{@code page-3-r90.png}），
     * 拿它当文件名，学生转过一次就再也认不出哪张是自己拍的了。原始上传名从上传那一刻起就不变。
     *
     * <p>写成标量子查询而不是 join：join 的连接条件要在每处查询里各写一遍，
     * 漏写一处不会报错，只会让那一处的文件名静默变成 null。
     */
    private static final String PAGE_COLUMNS = """
        sp.id, sp.page_no, sp.document_id, sp.document_page_no, sp.page_file_id, sp.thumbnail_file_id,
        sp.rotated_file_id, sp.rotation_degrees, sp.quality_status, sp.width, sp.height,
        (select sf.original_name from document_upload du
           join stored_file sf on sf.id = du.original_file_id
          where du.id = sp.document_id) as file_name
        """;

    private static VersionRow toVersion(ResultSet rs, int rowNum) throws SQLException {
        SubmissionVersionStatus status = SubmissionVersionStatus.parse(rs.getString("status"));
        return new VersionRow(rs.getLong("id"), rs.getLong("submission_id"), rs.getLong("assignment_id"),
            rs.getLong("student_id"), rs.getInt("version_no"), status, rs.getBoolean("is_current"),
            instant(rs, "submitted_at"), instant(rs, "locked_at"), instant(rs, "returned_at"),
            rs.getString("return_reason"), instant(rs, "created_at"));
    }

    private static SubmissionVersionView.Summary toSummary(ResultSet rs, int rowNum) throws SQLException {
        SubmissionVersionStatus status = SubmissionVersionStatus.parse(rs.getString("status"));
        return new SubmissionVersionView.Summary(rs.getLong("id"), rs.getInt("version_no"), status,
            rs.getBoolean("is_current"), status.editable(), instant(rs, "submitted_at"),
            instant(rs, "returned_at"), rs.getString("return_reason"), rs.getInt("page_count"));
    }

    private static PageRow toPage(ResultSet rs, int rowNum) throws SQLException {
        return new PageRow(rs.getLong("id"), rs.getInt("page_no"), rs.getLong("document_id"),
            rs.getInt("document_page_no"), rs.getString("file_name"), rs.getLong("page_file_id"),
            (Long) rs.getObject("thumbnail_file_id"), rs.getLong("rotated_file_id"),
            rs.getInt("rotation_degrees"), rs.getString("quality_status"),
            (Integer) rs.getObject("width"), (Integer) rs.getObject("height"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Optional<VersionRow> findCurrent(long assignmentId, long studentId) {
        return jdbc.sql(VERSION_SELECT + """
                where assignment_id = :assignmentId and student_id = :studentId and is_current = true
                """)
            .param("assignmentId", assignmentId)
            .param("studentId", studentId)
            .query(SubmissionVersionService::toVersion)
            .optional();
    }

    /**
     * 学生手上还没提交的那一版。
     *
     * <p>{@code optional()} 在两行以上会抛异常，这里是故意的：同一份作业出现两个可编辑版本
     * 说明 {@code uk_submission_version_no} 与作业行锁都失效了，属于数据损坏，
     * 应该以 500 暴露出来，而不是挑一个"最新的"继续跑。
     */
    private Optional<VersionRow> findEditable(long assignmentId, long studentId) {
        return jdbc.sql(VERSION_SELECT + " where assignment_id = :assignmentId and student_id = :studentId"
                + " and status in (" + EDITABLE_STATUSES + ")")
            .param("assignmentId", assignmentId)
            .param("studentId", studentId)
            .query(SubmissionVersionService::toVersion)
            .optional();
    }

    private VersionRow requireVersionForStudent(long studentId, long versionId) {
        return jdbc.sql(VERSION_SELECT + "where id = :id and student_id = :studentId")
            .param("id", versionId)
            .param("studentId", studentId)
            .query(SubmissionVersionService::toVersion)
            .optional()
            .orElseThrow(SubmissionVersionService::versionNotFound);
    }

    /** 教师侧走作业归属：版本 id 是学生的，老师能看的那一版必须挂在自己的作业上。 */
    private VersionRow requireVersionForTeacher(long teacherId, long versionId) {
        return jdbc.sql(VERSION_SELECT + """
                where id = :id
                  and assignment_id in (select id from assignment where teacher_id = :teacherId)
                """)
            .param("id", versionId)
            .param("teacherId", teacherId)
            .query(SubmissionVersionService::toVersion)
            .optional()
            .orElseThrow(SubmissionVersionService::versionNotFound);
    }

    private static DomainException versionNotFound() {
        return new DomainException("SUBMISSION_VERSION_NOT_FOUND", "提交记录不存在", HttpStatus.NOT_FOUND);
    }

    /**
     * 教师侧的归属校验，给校对链路用。
     *
     * <p>与 {@code requireVersionForTeacher} 查的是同一件事，只是返回一个不含时间戳的窄类型：
     * 校对链路要的是"哪一版、哪份作业、哪个学生、什么状态"，把整行递出去只会让它有机会
     * 依赖那些与它无关的列。
     */
    VersionRef requireForTeacher(long teacherId, long versionId) {
        return jdbc.sql("""
                select id, submission_id, assignment_id, student_id, version_no, status, is_current
                from submission_version
                where id = :id
                  and assignment_id in (select id from assignment where teacher_id = :teacherId)
                """)
            .param("id", versionId)
            .param("teacherId", teacherId)
            .query((rs, rowNum) -> new VersionRef(rs.getLong("id"), rs.getLong("submission_id"),
                rs.getLong("assignment_id"), rs.getLong("student_id"), rs.getInt("version_no"),
                SubmissionVersionStatus.parse(rs.getString("status")), rs.getBoolean("is_current")))
            .optional()
            .orElseThrow(SubmissionVersionService::versionNotFound);
    }

    /**
     * 能改的版本：存在、是自己的、还没提交、而且这份作业上还没有版本开始批改。
     *
     * <p>两种失败给两种回答：不存在与不是自己的都是 404（不泄露别人的 id 是否存在），
     * 已提交或已开始批改的是 409 —— "为什么改不了"是学生需要知道的事，跟"没有这一版"完全是两回事。
     */
    private VersionRow requireEditableVersion(long studentId, long versionId) {
        VersionRow version = requireVersionForStudent(studentId, versionId);
        requireEditableStatus(version);
        requireGradingNotStarted(version.assignmentId(), studentId);
        return version;
    }

    private PageRow requirePage(long versionId, long pageId) {
        return jdbc.sql("select " + PAGE_COLUMNS + """
                from submission_page sp
                where sp.id = :id and sp.submission_version_id = :versionId
                """)
            .param("id", pageId)
            .param("versionId", versionId)
            .query(SubmissionVersionService::toPage)
            .optional()
            .orElseThrow(() -> new DomainException("SUBMISSION_PAGE_NOT_FOUND",
                "这一页不存在", HttpStatus.NOT_FOUND));
    }

    private SubmissionVersionView detail(VersionRow version) {
        return new SubmissionVersionView(version.id(), version.assignmentId(), version.studentId(),
            version.versionNo(), version.status(), version.current(), version.status().editable(),
            version.submittedAt(), version.lockedAt(), version.returnedAt(), version.returnReason(),
            version.createdAt(), pagesOf(version.id()));
    }

    private List<SubmissionVersionView.PageView> pagesOf(long versionId) {
        return pageRows(versionId).stream()
            .map(page -> new SubmissionVersionView.PageView(page.id(), page.pageNo(), page.documentId(),
                page.documentPageNo(), page.fileName(), page.pageFileId(), page.thumbnailFileId(),
                page.rotatedFileId(), page.rotationDegrees(), page.qualityStatus(),
                page.width(), page.height()))
            .toList();
    }

    private List<PageRow> pageRows(long versionId) {
        return jdbc.sql("select " + PAGE_COLUMNS + """
                from submission_page sp
                where sp.submission_version_id = :versionId
                order by sp.page_no
                """)
            .param("versionId", versionId)
            .query(SubmissionVersionService::toPage)
            .list();
    }

    // ---------- 写入前的校验 ----------

    /**
     * 作业能不能交：必须是本人班里的、已发布、且还没结束。
     *
     * <p>未发布与别人的作业返回同一个 404，与 {@code StudentAssignmentController} 保持一致 ——
     * 学生不该从错误码里推断出"这个 id 是存在的"。
     */
    private AssignmentRow requireSubmittableAssignment(long studentId, long assignmentId) {
        AssignmentRow assignment = jdbc.sql("""
                select a.id, a.teacher_id, a.status, a.published_at
                from assignment a
                join student s on s.class_id = a.class_id
                where a.id = :assignmentId and s.id = :studentId
                """)
            .param("assignmentId", assignmentId)
            .param("studentId", studentId)
            .query((rs, rowNum) -> new AssignmentRow(rs.getLong("id"), rs.getLong("teacher_id"),
                rs.getString("status"), instant(rs, "published_at")))
            .optional()
            .orElseThrow(() -> new DomainException("ASSIGNMENT_NOT_FOUND", "作业不存在", HttpStatus.NOT_FOUND));
        if (assignment.publishedAt() == null) {
            throw new DomainException("ASSIGNMENT_NOT_FOUND", "作业不存在", HttpStatus.NOT_FOUND);
        }
        if (AssignmentStatus.parse(assignment.status()) == AssignmentStatus.COMPLETED) {
            throw new DomainException("SUBMISSION_CLOSED", "这份作业已经结束，不能再提交", HttpStatus.CONFLICT);
        }
        return assignment;
    }

    /**
     * 锁住作业行，把同一份作业上的并发动作排成一队。
     *
     * <p>版本号、{@code is_current} 与"上一版置为 SUPERSEDED"都是先读后写，
     * 没有这把锁，两个并发提交会算出同一个 version_no（撞唯一键报 500）或者互相把对方
     * 标成 SUPERSEDED。MySQL 与 H2 都支持 {@code select ... for update}。
     */
    private void lockAssignment(long assignmentId) {
        jdbc.sql("select id from assignment where id = :id for update")
            .param("id", assignmentId)
            .query(Long.class)
            .optional();
    }

    private long ensureSubmission(long assignmentId, long studentId) {
        GeneratedKeys.insertOrNull(jdbc, """
            insert into submission(assignment_id, student_id, status)
            select :assignmentId, :studentId, :status
            where not exists (
                select 1 from submission where assignment_id = :assignmentId and student_id = :studentId)
            """, statement -> statement
            .param("assignmentId", assignmentId)
            .param("studentId", studentId)
            .param("status", SUBMISSION_STATUS_IMPORTED));
        return jdbc.sql("select id from submission where assignment_id = :assignmentId and student_id = :studentId")
            .param("assignmentId", assignmentId)
            .param("studentId", studentId)
            .query(Long.class)
            .single();
    }

    private int nextVersionNo(long assignmentId, long studentId) {
        return jdbc.sql("""
                select coalesce(max(version_no), 0) + 1 from submission_version
                where assignment_id = :assignmentId and student_id = :studentId
                """)
            .param("assignmentId", assignmentId)
            .param("studentId", studentId)
            .query(Integer.class)
            .single();
    }

    /**
     * 现在能不能再开一版。
     *
     * <p>判断只看一件事：当前版本有没有被锁。{@code PROCESSING}、{@code NEEDS_REVIEW} 甚至
     * {@code CONFIRMED} 都还能重交 —— 批改开始前学生改主意是正常的，旧版会在新版提交时变成
     * {@code SUPERSEDED}。到了 {@code LOCKED} 就不行了，那时老师手上的评分已经对应着某一版作答。
     */
    private boolean canStartNewVersion(AssignmentRow assignment,
                                       List<SubmissionVersionView.Summary> versions) {
        if (AssignmentStatus.parse(assignment.status()) == AssignmentStatus.COMPLETED) {
            return false;
        }
        return versions.stream()
            .filter(SubmissionVersionView.Summary::current)
            .findFirst()
            .map(current -> current.status() != SubmissionVersionStatus.LOCKED)
            .orElse(true);
    }

    /**
     * 这一版已占用的上传字节数。
     *
     * <p>只算 {@code document_upload.original_file_id} 一份：渲染页与缩略图是服务端派生出来的，
     * 把它们也计入等于对学生上传的原图收了两遍钱，而学生看到的配额说明讲的是"能传多大的答卷"。
     */
    private long storedBytes(long versionId) {
        return jdbc.sql("""
                select coalesce(sum(sf.size_bytes), 0)
                from document_upload du
                join stored_file sf on sf.id = du.original_file_id
                where du.submission_version_id = :versionId
                """)
            .param("versionId", versionId)
            .query(Long.class)
            .single();
    }

    private long teacherIdOf(long assignmentId) {
        return jdbc.sql("select teacher_id from assignment where id = :id")
            .param("id", assignmentId)
            .query(Long.class)
            .single();
    }

    private List<PdfPageRenderer.PageImage> renderPdf(byte[] content) {
        try {
            return pdfRenderer.render(content);
        } catch (IOException exception) {
            // inspect 已经确认过这份 PDF 能打开，走到这里说明两次读取之间文件被截断了。
            throw new DomainException("FILE_NOT_DECODABLE", "文件已损坏或无法解析",
                HttpStatus.UNPROCESSABLE_ENTITY, exception);
        }
    }

    /** 派生页面图的名字：保留原文件名当上下文，扩展名说实话（它确实是渲染出来的 PNG）。 */
    private static String pageName(String originalName, int pageNo) {
        int dot = originalName.lastIndexOf('.');
        String stem = dot > 0 ? originalName.substring(0, dot) : originalName;
        return stem + "-p" + pageNo + ".png";
    }

    private byte[] readStudentFile(long studentId, long fileId) {
        StoredFileService.StoredFileDownload download = files.openForStudent(studentId, fileId);
        try (InputStream content = download.content()) {
            return content.readAllBytes();
        } catch (IOException exception) {
            throw new DomainException("STORAGE_READ_FAILED", "文件内容暂时不可用",
                HttpStatus.SERVICE_UNAVAILABLE, exception);
        }
    }

    /** 页面图没有缩略图时补一张，用于"转回 0 度"这条不改图的路径。 */
    private long storeThumbnail(long studentId, long pageFileId, int pageNo) {
        return files.storeStudentFile(studentId, "thumb-page-" + pageNo + ".png", "image/png",
            ImageTransforms.thumbnail(readStudentFile(studentId, pageFileId))).id();
    }

    /** 写一条审计。审计写上失败要连事务一起回滚：没有留痕的状态变化不能算发生过。 */
    private void audit(long versionId, long assignmentId, long studentId, SubmissionAuditAction action,
                       SubmissionAuditAction.ActorRole role, Long actorId, String detail) {
        jdbc.sql("""
                insert into submission_audit(submission_version_id, assignment_id, student_id,
                                             action, actor_role, actor_id, detail)
                values (:versionId, :assignmentId, :studentId, :action, :role, :actorId, :detail)
                """)
            .param("versionId", versionId)
            .param("assignmentId", assignmentId)
            .param("studentId", studentId)
            .param("action", action.name())
            .param("role", role.name())
            .param("actorId", actorId)
            .param("detail", detail)
            .update();
    }
}
