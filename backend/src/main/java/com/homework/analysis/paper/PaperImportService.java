package com.homework.analysis.paper;

import com.homework.analysis.assignment.AssignmentService;
import com.homework.analysis.assignment.AssignmentStatus;
import com.homework.analysis.assignment.AssignmentView;
import com.homework.analysis.classroom.ClassroomService;
import com.homework.analysis.document.FileStorage;
import com.homework.analysis.document.ImageTransforms;
import com.homework.analysis.document.StoredFileService;
import com.homework.analysis.ocr.OcrResult;
import com.homework.analysis.ocr.OcrTaskWorker;
import com.homework.analysis.question.QuestionCommand;
import com.homework.analysis.question.QuestionDifficulty;
import com.homework.analysis.question.QuestionService;
import com.homework.analysis.question.QuestionType;
import com.homework.analysis.question.QuestionView;
import com.homework.analysis.question.RubricCommand;
import com.homework.analysis.shared.error.DomainException;
import com.homework.analysis.shared.jdbc.GeneratedKeys;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 教师整卷导入。
 *
 * <p>"导入"就是一份状态为 {@code OCR_REVIEW} 的作业加上它的文档，因此接口路径里的 {@code {id}}
 * 是**作业 id**：作业本来就是这条链路的状态宿主（导入 → 校对 → 发布 → 学生提交），
 * 另建一张"导入表"只会让同一个生命周期在两个地方各记一份。
 *
 * <p>三条贯穿全类的规则：
 * <ol>
 *   <li><b>识别结果只写候选</b>。{@link #process} 不会创建任何 {@code question}；
 *       唯一创建题目的地方是 {@link #confirm}，且在那里是一次全有或全无的事务。</li>
 *   <li><b>确认可重复调用</b>。第二次确认返回同一份作业而不是再插一遍题目——
 *       网络重试、用户连点两下都可能触发，靠"客户端别点两次"是防不住的。</li>
 *   <li><b>越权与不存在返回同一个 404</b>。教师的作业 id 是自己可见范围内的连续数字，
 *       区分"不存在"与"不是你的"等于把别人的作业 id 变成可枚举的信息。</li>
 * </ol>
 */
@Service
public class PaperImportService {

    private static final Logger log = LoggerFactory.getLogger(PaperImportService.class);

    static final String KIND_EXAM_PAPER = "EXAM_PAPER";
    static final String KIND_ANSWER_KEY = "ANSWER_KEY";

    /**
     * 低于这个置信度就在候选上标警告。阈值只影响提示，不能绕过教师确认。
     *
     * <p>公开是因为答卷校对（{@code AnswerExtractionService}）用的是同一个门槛：
     * 两份识别结果来自同一个引擎，"多不确定要提醒人看"不该在两个包里各有一套答案。
     */
    public static final double LOW_CONFIDENCE_THRESHOLD = 0.85;

    static final String WARN_LOW_CONFIDENCE = "LOW_CONFIDENCE";
    static final String WARN_MISSING_QUESTION_CODE = "MISSING_QUESTION_CODE";
    static final String WARN_QUESTION_TYPE_UNKNOWN = "QUESTION_TYPE_UNKNOWN";
    static final String WARN_SCORE_NOT_DETECTED = "SCORE_NOT_DETECTED";
    static final String WARN_OVERLAPPING_QUESTION_BOX = "OVERLAPPING_QUESTION_BOX";
    static final String WARN_AMBIGUOUS_CROSS_PAGE_GROUP = "AMBIGUOUS_CROSS_PAGE_GROUP";
    static final String WARN_ANSWER_KEY_UNMATCHED = "ANSWER_KEY_UNMATCHED";
    static final String WARN_ANSWER_KEY_MISSING = "ANSWER_KEY_MISSING";

    private static final String STATUS_CONFIRMED = "CONFIRMED";
    private static final String STATUS_NEEDS_REVIEW = "NEEDS_REVIEW";
    private static final String REVIEW_CONFIRMED = "CONFIRMED";
    private static final Set<String> ALLOWED_REVIEW_STATUSES = Set.of("PENDING", "CONFIRMED", "REJECTED");
    private static final Set<String> DOCUMENT_KINDS = Set.of(KIND_EXAM_PAPER, KIND_ANSWER_KEY);

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<Long>> LONG_LIST = new TypeReference<>() {};
    private static final TypeReference<List<RubricCommand>> RUBRIC_LIST = new TypeReference<>() {};

    /** 区域读取的公共列与连接；需要附加条件的地方直接在其后拼接。 */
    private static final String REGION_SELECT = """
        select dr.id, dr.page_id, dp.page_file_id, dr.region_type, dr.x, dr.y, dr.width, dr.height,
               dr.ocr_text, dr.ocr_latex, dr.confidence, dr.crop_file_id, dr.review_status
        from document_region dr
        join document_page dp on dp.id = dr.page_id
        """;

    private final JdbcClient jdbc;
    private final ClassroomService classrooms;
    private final AssignmentService assignments;
    private final QuestionService questions;
    private final StoredFileService files;
    private final FileStorage storage;
    private final OcrTaskWorker ocrWorker;
    private final PaperCandidateMaterializer materializer;
    private final ObjectMapper objectMapper;

    PaperImportService(JdbcClient jdbc, ClassroomService classrooms, AssignmentService assignments,
                       QuestionService questions, StoredFileService files, FileStorage storage,
                       OcrTaskWorker ocrWorker, PaperCandidateMaterializer materializer,
                       ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.classrooms = classrooms;
        this.assignments = assignments;
        this.questions = questions;
        this.files = files;
        this.storage = storage;
        this.ocrWorker = ocrWorker;
        this.materializer = materializer;
        this.objectMapper = objectMapper;
    }

    /** 创建导入。班级归属在这里校验一次，后续所有接口都从作业反查，不再信任请求里的班级。 */
    @Transactional
    public PaperImportView create(long teacherId, CreateCommand command) {
        classrooms.get(teacherId, command.classId());
        long assignmentId = GeneratedKeys.insert(jdbc, """
            insert into assignment(teacher_id, class_id, title, status)
            values (:teacherId, :classId, :title, 'OCR_REVIEW')
            """, statement -> statement
            .param("teacherId", teacherId)
            .param("classId", command.classId())
            .param("title", command.title().trim()));
        return get(teacherId, assignmentId);
    }

    /**
     * 上传整卷文件。
     *
     * <p>空白卷与答案卷各自只能有一份，重复上传直接拒绝而不是替换：替换会让已经跑过的识别结果
     * 指向另一份内容，而 {@code ocr_task} 的幂等键是文件哈希，新文件会被判成"需要重新识别"，
     * 于是同一个文档下出现两份互相矛盾的候选。
     */
    @Transactional
    public PaperImportView attachFile(long teacherId, long assignmentId, String kind, String originalName,
                                      String contentType, byte[] content) {
        requireImport(teacherId, assignmentId);
        ensureNotConfirmed(assignmentId);
        String documentKind = requireKind(kind);
        if (findDocument(assignmentId, documentKind).isPresent()) {
            throw new DomainException("PAPER_IMPORT_DOCUMENT_EXISTS",
                "该类型的文件已经上传过，不能重复上传", HttpStatus.CONFLICT);
        }
        StoredFileService.StoredFileView stored =
            files.storeTeacherFile(teacherId, originalName, contentType, content);
        GeneratedKeys.insert(jdbc, """
            insert into document_upload(document_kind, teacher_id, assignment_id, original_file_id, status)
            values (:documentKind, :teacherId, :assignmentId, :fileId, 'PENDING')
            """, statement -> statement
            .param("documentKind", documentKind)
            .param("teacherId", teacherId)
            .param("assignmentId", assignmentId)
            .param("fileId", stored.id()));
        return get(teacherId, assignmentId);
    }

    /**
     * 跑识别并物化候选。
     *
     * <p>整个过程在一个事务里，包括对外部 OCR 服务的调用。这不是为了"回滚网络请求"——
     * 而是为了让"任务状态"与"候选数据"永远一致：如果候选物化到一半失败而任务状态已经落库，
     * 教师重试时看到的是一个"已完成但候选不全"的导入，而系统无法判断该不该重跑识别。
     *
     * <p>幂等性有两层：{@code ocr_task} 靠 {@code (文件哈希, 文档类型, 处理版本)} 唯一键保证同一份
     * 文件不会产生第二条任务；候选只在尚未生成时物化，因此重复调用 {@code process} 不会把教师的
     * 校对结果覆盖掉。
     */
    @Transactional
    public PaperImportView process(long teacherId, long assignmentId) {
        requireImport(teacherId, assignmentId);
        ensureNotConfirmed(assignmentId);
        DocumentRow examPaper = findDocument(assignmentId, KIND_EXAM_PAPER)
            .orElseThrow(() -> new DomainException("PAPER_IMPORT_EXAM_PAPER_REQUIRED",
                "请先上传空白试卷", HttpStatus.CONFLICT));

        for (DocumentRow document : documents(assignmentId)) {
            ensureOcrTask(document);
            ocrWorker.processOne(teacherId, document.documentId());
        }

        if (candidates(assignmentId, KIND_EXAM_PAPER).isEmpty()) {
            materialize(assignmentId, examPaper);
            findDocument(assignmentId, KIND_ANSWER_KEY)
                .filter(answerKey -> STATUS_NEEDS_REVIEW.equals(statusOf(answerKey.documentId())))
                .ifPresent(answerKey -> materialize(assignmentId, answerKey));
            matchAnswerKey(assignmentId);
        }
        return get(teacherId, assignmentId);
    }

    public PaperImportView get(long teacherId, long assignmentId) {
        ImportRow row = requireImport(teacherId, assignmentId);
        List<DocumentRow> documents = documents(assignmentId);
        List<PaperQuestionCandidate> candidates = candidates(assignmentId, null);

        Map<Long, Long> candidateByRegion = new TreeMap<>();
        for (PaperQuestionCandidate candidate : candidates) {
            candidate.sourceRegionIds().forEach(regionId -> candidateByRegion.put(regionId, candidate.id()));
        }

        List<PaperImportView.DocumentView> documentViews = new ArrayList<>();
        for (DocumentRow document : documents) {
            List<PaperImportView.PageView> pages = new ArrayList<>();
            for (PageRow page : pages(document.documentId())) {
                List<PaperImportView.RegionView> regions = regions(page.pageId()).stream()
                    .map(region -> new PaperImportView.RegionView(region.regionId(), region.regionType(),
                        region.x(), region.y(), region.width(), region.height(), region.ocrText(),
                        region.ocrLatex(), region.confidence(), region.cropFileId(), region.reviewStatus(),
                        candidateByRegion.get(region.regionId())))
                    .toList();
                pages.add(new PaperImportView.PageView(page.pageId(), page.pageNo(), page.pageFileId(),
                    page.thumbnailFileId(), page.width(), page.height(), regions));
            }
            documentViews.add(new PaperImportView.DocumentView(document.documentId(), document.documentKind(),
                document.status(), document.failureCode(), document.ocrStatus(), document.ocrFailureCode(),
                document.pageCount(), pages));
        }

        boolean confirmed = documents.stream().anyMatch(document -> STATUS_CONFIRMED.equals(document.status()));
        return new PaperImportView(row.assignmentId(), row.classId(), row.title(), row.status(), confirmed,
            documentViews, candidates, aggregateWarnings(documents, candidates));
    }

    /**
     * 校对编辑（区域几何 + 候选题字段）。
     *
     * <p>顺序是先改候选、再改区域。候选的版本检查会抛 409，而两个写操作在同一个事务里，
     * 所以"版本过期"时区域也不会被改动——反过来写就会出现"框移动了但字段没保存"的半截状态。
     *
     * <p>三种动作互斥：{@code split} 拆出新题、{@code candidateId} 为空表示摘出区域、
     * 其余情况是把区域并入指定候选并更新它的字段。
     */
    @Transactional
    public PaperImportView patchRegion(long teacherId, long assignmentId, long regionId,
                                      PaperRegionPatchCommand command) {
        requireImport(teacherId, assignmentId);
        ensureNotConfirmed(assignmentId);
        RegionRow region = requireRegion(assignmentId, regionId);

        if (Boolean.TRUE.equals(command.split())) {
            splitRegion(assignmentId, region, command);
        } else if (command.candidateId() == null) {
            detachRegion(assignmentId, regionId);
        } else {
            applyCandidateEdit(assignmentId, command.candidateId(), command.version(),
                command.candidate(), regionId);
        }
        boolean geometryChanged = applyGeometry(region, command);
        if (Boolean.TRUE.equals(command.createCrop())) {
            createCrop(teacherId, regionId);
        } else if (geometryChanged) {
            // 框动过之后旧裁剪图已经不对应任何东西，留着它只会让教师对着错位的图核对。
            jdbc.sql("update document_region set crop_file_id = null, updated_at = current_timestamp(3) where id = :id")
                .param("id", regionId)
                .update();
        }
        return get(teacherId, assignmentId);
    }

    /**
     * 把一块区域从原候选题拆出来，单独组成一道新题。
     *
     * <p>新题只继承题型与难度：题号、题干、分值都必须由教师重新填。
     * 继承题号会让两道题的编码相同，确认时被唯一约束拒绝；继承分值则会让
     * "评分项之和等于总分"这条规则在教师还没看懂新题之前就失效。
     *
     * <p>原题只剩这一块区域时拒绝拆分——拆完它会变成一道没有来源区域、也无法校对的空题。
     * 与其留下这样一行，不如让教师先确认自己是不是点错了。
     */
    private void splitRegion(long assignmentId, RegionRow region, PaperRegionPatchCommand command) {
        if (command.candidateId() == null) {
            throw new DomainException("PAPER_CANDIDATE_REQUIRED", "拆分需要指定原候选题");
        }
        if (command.version() == null) {
            throw new DomainException("PAPER_CANDIDATE_VERSION_REQUIRED",
                "修改候选题必须带上当前版本号");
        }
        PaperQuestionCandidate owner = requireCandidate(assignmentId, command.candidateId());
        if (owner.version() != command.version()) {
            throw new DomainException("OCR_REVIEW_CONFLICT",
                "这道题已被其他操作修改，请刷新后重试", HttpStatus.CONFLICT);
        }
        if (!owner.sourceRegionIds().contains(region.regionId())) {
            throw new DomainException("REGION_NOT_IN_CANDIDATE", "这块区域不属于这道候选题");
        }
        if (owner.sourceRegionIds().size() <= 1) {
            throw new DomainException("SPLIT_WOULD_EMPTY_CANDIDATE",
                "这道题只剩这一块区域，拆分后原题会没有任何内容");
        }

        Integer maxOrder = jdbc.sql("""
                select max(order_no) from paper_question_candidate where document_id = :documentId
                """)
            .param("documentId", owner.documentId())
            .query(Integer.class)
            .optional()
            .orElse(null);
        jdbc.sql("""
            insert into paper_question_candidate(assignment_id, document_id, document_kind, order_no,
                question_type, content, accepted_answers, rubric_items, difficulty, source_region_ids,
                asset_region_ids, confidence, warnings, review_status, version)
            values (:assignmentId, :documentId, :documentKind, :orderNo,
                :questionType, :content, '[]', '[]', :difficulty, :sourceRegionIds,
                '[]', :confidence, '[]', 'PENDING', 0)
            """)
            .param("assignmentId", assignmentId)
            .param("documentId", owner.documentId())
            .param("documentKind", owner.documentKind())
            .param("orderNo", (maxOrder == null ? 0 : maxOrder) + 1)
            .param("questionType", name(owner.questionType()))
            .param("content", region.ocrText() == null ? null : region.ocrText().trim())
            .param("difficulty", name(owner.difficulty()))
            .param("sourceRegionIds", writeJson(List.of(region.regionId())))
            .param("confidence", region.confidence())
            .update();

        List<Long> remaining = owner.sourceRegionIds().stream()
            .filter(id -> id != region.regionId())
            .toList();
        jdbc.sql("""
            update paper_question_candidate
            set source_region_ids = :sourceRegionIds, version = version + 1,
                review_status = 'PENDING', updated_at = current_timestamp(3)
            where id = :id
            """)
            .param("sourceRegionIds", writeJson(remaining))
            .param("id", owner.id())
            .update();
    }

    /**
     * 一次性把候选变成题目。
     *
     * <p>校验复用 {@link QuestionService}：题型与答案的一致性、评分项之和等于总分、知识点归属，
     * 这些规则只能有一份实现。代价是逐题创建，任何一道题不合法都会让整批回滚——
     * 而这正是要的：一份"大部分题入库了但缺两道"的作业，比一次失败更难被发现，
     * 教师会以为导入成功而学生实际少做了两道题。
     */
    @Transactional
    public AssignmentView confirm(long teacherId, long assignmentId) {
        requireImport(teacherId, assignmentId);

        List<DocumentRow> documents = documents(assignmentId);
        if (documents.stream().anyMatch(document -> STATUS_CONFIRMED.equals(document.status()))) {
            // 幂等出口：重复确认返回同一份作业，而不是再插一遍题目。
            return assignments.requireOwned(teacherId, assignmentId);
        }
        findDocument(assignmentId, KIND_EXAM_PAPER)
            .orElseThrow(() -> new DomainException("PAPER_IMPORT_EXAM_PAPER_REQUIRED",
                "请先上传空白试卷", HttpStatus.CONFLICT));

        List<PaperQuestionCandidate> examCandidates = candidates(assignmentId, KIND_EXAM_PAPER);
        if (examCandidates.isEmpty()) {
            throw new DomainException("PAPER_IMPORT_NO_CANDIDATE",
                "还没有可确认的候选题，请先完成识别", HttpStatus.CONFLICT);
        }
        Map<String, PaperQuestionCandidate> answerKey = new TreeMap<>();
        for (PaperQuestionCandidate candidate : candidates(assignmentId, KIND_ANSWER_KEY)) {
            if (candidate.questionCode() != null) {
                answerKey.putIfAbsent(candidate.questionCode(), candidate);
            }
        }

        int order = 1;
        for (PaperQuestionCandidate candidate : examCandidates) {
            QuestionCommand questionCommand = toQuestionCommand(candidate, answerKey);
            QuestionView question = questions.create(teacherId, questionCommand);
            jdbc.sql("""
                insert into assignment_question(assignment_id, question_id, question_order)
                values (:assignmentId, :questionId, :questionOrder)
                """)
                .param("assignmentId", assignmentId)
                .param("questionId", question.id())
                .param("questionOrder", order++)
                .update();
            insertAssets(assignmentId, question.id(), candidate);
        }

        jdbc.sql("""
            update document_upload set status = 'CONFIRMED', updated_at = current_timestamp(3)
            where assignment_id = :assignmentId and deleted_at is null
              and document_kind in ('EXAM_PAPER', 'ANSWER_KEY')
            """)
            .param("assignmentId", assignmentId)
            .update();
        jdbc.sql("""
            update ocr_task set status = 'CONFIRMED', updated_at = current_timestamp(3)
            where document_id in (select id from document_upload where assignment_id = :assignmentId)
            """)
            .param("assignmentId", assignmentId)
            .update();
        log.info("整卷导入确认完成：assignmentId={} 题目数={}", assignmentId, order - 1);
        return assignments.requireOwned(teacherId, assignmentId);
    }

    // ---------- 确认的内部步骤 ----------

    private QuestionCommand toQuestionCommand(PaperQuestionCandidate candidate,
                                              Map<String, PaperQuestionCandidate> answerKey) {
        String code = trimToNull(candidate.questionCode());
        if (code == null) {
            throw incomplete(candidate, "缺少题号");
        }
        if (candidate.questionType() == null) {
            throw incomplete(candidate, "未确定题型");
        }
        String content = trimToNull(candidate.content());
        if (content == null) {
            throw incomplete(candidate, "题干为空");
        }
        if (candidate.totalScore() == null || candidate.totalScore() < 1) {
            throw incomplete(candidate, "未填写分值");
        }
        if (candidate.primaryKnowledgePointId() == null) {
            throw incomplete(candidate, "未指定知识点");
        }
        String standardAnswer = trimToNull(candidate.standardAnswer());
        if (standardAnswer == null) {
            // 答案卷按题号提供标准答案；这是它唯一的作用。
            PaperQuestionCandidate matched = answerKey.get(code);
            standardAnswer = matched == null ? null : trimToNull(matched.standardAnswer());
        }
        return new QuestionCommand(code, candidate.questionType(), content, standardAnswer,
            candidate.totalScore(), candidate.difficulty() == null ? QuestionDifficulty.MEDIUM : candidate.difficulty(),
            candidate.primaryKnowledgePointId(), List.of(), candidate.acceptedAnswers(), candidate.rubricItems());
    }

    private static DomainException incomplete(PaperQuestionCandidate candidate, String reason) {
        return new DomainException("OCR_REVIEW_INCOMPLETE",
            "第 " + candidate.orderNo() + " 题还不能入库：" + reason);
    }

    /**
     * 用来源区域的裁剪图建题图。
     *
     * <p>没有裁剪图的来源区域会被拒绝，而不是跳过：静默跳过等于交付了一道"少了配图"的题，
     * 而教师已经在界面上确认过它——校对的全部意义就是让入库的内容与看到的一致。
     */
    private void insertAssets(long assignmentId, long questionId, PaperQuestionCandidate candidate) {
        List<Long> regionIds = candidate.assetRegionIds();
        if (regionIds.isEmpty()) {
            return;
        }
        // 按 assignmentId 查而不是按区域 id 查：区域 id 是全局自增的，只按 id 取会让别人导入里的
        // 区域被当成自己的题图来源，而校验层已经确认过"这次导入只有这些区域"。
        Map<Long, RegionRow> byId = new TreeMap<>();
        for (RegionRow region : requireRegions(assignmentId, regionIds)) {
            byId.put(region.regionId(), region);
        }
        int sortOrder = 1;
        for (Long regionId : regionIds) {
            RegionRow region = byId.get(regionId);
            if (region == null || region.cropFileId() == null) {
                throw new DomainException("QUESTION_ASSET_NOT_READY",
                    "第 " + candidate.orderNo() + " 题的题图还没裁剪好，请先在原卷上框选并裁剪");
            }
            QuestionAssetRole role = "FIGURE".equals(region.regionType())
                ? QuestionAssetRole.STEM_FIGURE : QuestionAssetRole.SOURCE_CROP;
            jdbc.sql("""
                insert into question_asset(question_id, file_id, role, sort_order)
                values (:questionId, :fileId, :role, :sortOrder)
                """)
                .param("questionId", questionId)
                .param("fileId", region.cropFileId())
                .param("role", role.name())
                .param("sortOrder", sortOrder++)
                .update();
        }
    }

    // ---------- 候选与区域的读写 ----------

    private void applyCandidateEdit(long assignmentId, long candidateId, Integer expectedVersion,
                                    PaperQuestionCommand command, long regionId) {
        if (expectedVersion == null) {
            throw new DomainException("PAPER_CANDIDATE_VERSION_REQUIRED",
                "修改候选题必须带上当前版本号");
        }
        PaperQuestionCandidate current = requireCandidate(assignmentId, candidateId);
        if (current.version() != expectedVersion) {
            throw new DomainException("OCR_REVIEW_CONFLICT",
                "这道题已被其他操作修改，请刷新后重试", HttpStatus.CONFLICT);
        }
        PaperQuestionCommand edit = command == null
            ? new PaperQuestionCommand(null, null, null, null, null, null, null, null, null, null, null)
            : command;

        List<Long> sources = new ArrayList<>(current.sourceRegionIds());
        if (!sources.contains(regionId)) {
            sources.add(regionId);
            // 区域 id 是按阅读顺序分配的，排序后仍是阅读顺序；追加一个早期的区域若直接放到尾部，
            // 题干拼接顺序就会与原卷不一致。
            sources.sort(Comparator.naturalOrder());
        }

        int updated = jdbc.sql("""
            update paper_question_candidate set
                question_code = :questionCode, question_type = :questionType, content = :content,
                standard_answer = :standardAnswer, accepted_answers = :acceptedAnswers,
                rubric_items = :rubricItems, total_score = :totalScore, difficulty = :difficulty,
                primary_knowledge_point_id = :primaryKnowledgePointId,
                asset_region_ids = :assetRegionIds, source_region_ids = :sourceRegionIds,
                review_status = 'PENDING', version = version + 1, updated_at = current_timestamp(3)
            where id = :id and assignment_id = :assignmentId and version = :expectedVersion
            """)
            .param("questionCode", pick(edit.questionCode(), current.questionCode()))
            .param("questionType", name(pick(edit.questionType(), current.questionType())))
            .param("content", pick(edit.content(), current.content()))
            .param("standardAnswer", pick(edit.standardAnswer(), current.standardAnswer()))
            .param("acceptedAnswers", writeJson(edit.acceptedAnswers() == null
                ? current.acceptedAnswers() : edit.acceptedAnswers()))
            .param("rubricItems", writeJson(edit.rubricItems() == null
                ? current.rubricItems() : edit.rubricItems()))
            .param("totalScore", edit.totalScore() == null ? current.totalScore() : edit.totalScore())
            .param("difficulty", name(pick(edit.difficulty(), current.difficulty())))
            .param("primaryKnowledgePointId", edit.primaryKnowledgePointId() == null
                ? current.primaryKnowledgePointId() : edit.primaryKnowledgePointId())
            .param("assetRegionIds", writeJson(edit.assetRegionIds() == null
                ? current.assetRegionIds() : edit.assetRegionIds()))
            .param("sourceRegionIds", writeJson(sources))
            .param("id", candidateId)
            .param("assignmentId", assignmentId)
            .param("expectedVersion", expectedVersion)
            .update();
        if (updated == 0) {
            throw new DomainException("OCR_REVIEW_CONFLICT",
                "这道题已被其他操作修改，请刷新后重试", HttpStatus.CONFLICT);
        }
        detachFromOtherCandidates(assignmentId, current.documentId(), regionId, candidateId);
        if (edit.orderNo() != null) {
            reorder(current.documentId(), candidateId, edit.orderNo());
        }
    }

    /** 一个区域只能属于一道题；合并到新题时必须从旧题上摘掉，否则它在两个候选里各出现一次。 */
    private void detachFromOtherCandidates(long assignmentId, long documentId, long regionId, long keepCandidateId) {
        for (PaperQuestionCandidate candidate : candidates(assignmentId, null)) {
            if (candidate.id() == keepCandidateId || candidate.documentId() != documentId) {
                continue;
            }
            if (!candidate.sourceRegionIds().contains(regionId)) {
                continue;
            }
            List<Long> remaining = candidate.sourceRegionIds().stream()
                .filter(id -> id != regionId)
                .toList();
            jdbc.sql("""
                update paper_question_candidate
                set source_region_ids = :sourceRegionIds, version = version + 1,
                    review_status = 'PENDING', updated_at = current_timestamp(3)
                where id = :id
                """)
                .param("sourceRegionIds", writeJson(remaining))
                .param("id", candidate.id())
                .update();
        }
    }

    private void detachRegion(long assignmentId, long regionId) {
        List<PaperQuestionCandidate> all = candidates(assignmentId, null);
        for (PaperQuestionCandidate candidate : all) {
            if (!candidate.sourceRegionIds().contains(regionId)) {
                continue;
            }
            List<Long> remaining = candidate.sourceRegionIds().stream()
                .filter(id -> id != regionId)
                .toList();
            jdbc.sql("""
                update paper_question_candidate
                set source_region_ids = :sourceRegionIds, version = version + 1,
                    review_status = 'PENDING', updated_at = current_timestamp(3)
                where id = :id
                """)
                .param("sourceRegionIds", writeJson(remaining))
                .param("id", candidate.id())
                .update();
        }
    }

    /**
     * 重排候选题顺序。
     *
     * <p>两趟写：先把这批候选改成负数序号，再改成目标序号。唯一键 {@code (document_id, order_no)}
     * 在教师把第 3 题挪到第 5 题时必然出现短暂的重号，一趟写完不是撞约束就是得靠
     * {@code order_no = order_no + 1000} 之类的偏移技巧，而偏移量迟早会被真实数据撞上。
     */
    private void reorder(long documentId, long candidateId, int targetOrder) {
        List<Long> ids = new ArrayList<>(jdbc.sql("""
                select id from paper_question_candidate
                where document_id = :documentId and id <> :candidateId order by order_no
                """)
            .param("documentId", documentId)
            .param("candidateId", candidateId)
            .query(Long.class)
            .list());
        ids.add(Math.min(targetOrder - 1, ids.size()), candidateId);
        for (int index = 0; index < ids.size(); index++) {
            jdbc.sql("update paper_question_candidate set order_no = :orderNo, updated_at = current_timestamp(3) where id = :id")
                .param("orderNo", -(index + 1))
                .param("id", ids.get(index))
                .update();
        }
        for (int index = 0; index < ids.size(); index++) {
            jdbc.sql("update paper_question_candidate set order_no = :orderNo, updated_at = current_timestamp(3) where id = :id")
                .param("orderNo", index + 1)
                .param("id", ids.get(index))
                .update();
        }
    }

    private boolean applyGeometry(RegionRow region, PaperRegionPatchCommand command) {
        String regionType = command.regionType() == null ? region.regionType()
            : command.regionType().trim().toUpperCase(java.util.Locale.ROOT);
        Double x = command.x() == null ? region.x() : command.x();
        Double y = command.y() == null ? region.y() : command.y();
        Double width = command.width() == null ? region.width() : command.width();
        Double height = command.height() == null ? region.height() : command.height();
        String reviewStatus = command.reviewStatus() == null ? region.reviewStatus()
            : requireReviewStatus(command.reviewStatus());
        String ocrText = command.ocrText() == null ? region.ocrText() : command.ocrText();

        boolean geometryChanged = x != region.x() || y != region.y()
            || width != region.width() || height != region.height();
        jdbc.sql("""
            update document_region
            set region_type = :regionType, x = :x, y = :y, width = :width, height = :height,
                ocr_text = :ocrText, review_status = :reviewStatus,
                revision = revision + 1, updated_at = current_timestamp(3)
            where id = :id
            """)
            .param("regionType", regionType)
            .param("x", x)
            .param("y", y)
            .param("width", width)
            .param("height", height)
            .param("ocrText", ocrText)
            .param("reviewStatus", reviewStatus)
            .param("id", region.regionId())
            .update();
        return geometryChanged;
    }

    private static String requireReviewStatus(String raw) {
        String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!ALLOWED_REVIEW_STATUSES.contains(normalized)) {
            throw new DomainException("REGION_REVIEW_STATUS_INVALID", "无法识别的校对状态：" + raw);
        }
        return normalized;
    }

    /** 按区域几何裁出题图并存成私有文件；存文件与写区域在同一个事务里。 */
    private void createCrop(long teacherId, long regionId) {
        RegionRow region = requireRegionRow(regionId);
        byte[] pageBytes = readStoredBytes(region.pageFileId());
        byte[] cropped = ImageTransforms.crop(pageBytes, region.x(), region.y(), region.width(), region.height());
        StoredFileService.StoredFileView stored = files.storeTeacherFile(teacherId,
            "region-" + regionId + "-crop.png", "image/png", cropped);
        jdbc.sql("update document_region set crop_file_id = :fileId, updated_at = current_timestamp(3) where id = :id")
            .param("fileId", stored.id())
            .param("id", regionId)
            .update();
    }

    // ---------- OCR 任务与物化 ----------

    /**
     * 每个文档只建一条 OCR 任务。
     *
     * <p>幂等靠唯一键而不是先查后插：先查后插在并发下两个请求都会查到"不存在"，
     * 然后其中一方撞唯一键报 500。这里让数据库来裁决，撞键就意味着"别人已经建好了"。
     *
     * <p>判重按文档，不按文件内容（V11 起唯一键是 {@code (document_id, processing_version)}）。
     * 按内容判重会把"学生又把同一张照片交了一遍"这种情形误判成"这份文件已经识别过了"，
     * 于是第二份文档永远拿不到任务，学生在"待识别"上一直等。
     */
    private void ensureOcrTask(DocumentRow document) {
        Long inserted = GeneratedKeys.insertOrNull(jdbc, """
            insert into ocr_task(document_id, document_kind, storage_sha256, status, processing_version)
            select :documentId, :documentKind, sf.sha256, 'PENDING', 1
            from stored_file sf
            where sf.id = :fileId
              and not exists (
                select 1 from ocr_task t
                where t.document_id = :documentId and t.processing_version = 1)
            """, statement -> statement
            .param("documentId", document.documentId())
            .param("documentKind", document.documentKind())
            .param("fileId", document.originalFileId()));
        if (inserted == null) {
            log.debug("OCR 任务已存在，复用：documentId={} kind={}", document.documentId(), document.documentKind());
        }
    }

    private void materialize(long assignmentId, DocumentRow document) {
        OcrResult result = readOcrResult(document.documentId());
        List<Long> candidateIds = materializer.materialize(document.teacherId(), new PaperCandidateMaterializer.SourceDocument(
            assignmentId, document.documentId(), document.documentKind(),
            document.originalFileId(), document.mimeType(), readStoredBytes(document.originalFileId())), result);
        log.info("候选已物化：documentId={} kind={} 候选数={}",
            document.documentId(), document.documentKind(), candidateIds.size());
    }

    /**
     * 空白卷与答案卷按题号对齐。
     *
     * <p>两个方向都要提示：答案卷多出来的题说明空白卷漏检（学生会少做一道），
     * 空白卷缺答案说明标准答案没进来（AI 评分会缺少依据）。只提示一个方向等于放走一半问题。
     */
    private void matchAnswerKey(long assignmentId) {
        Map<String, PaperQuestionCandidate> examByCode = new TreeMap<>();
        Map<String, PaperQuestionCandidate> keyByCode = new TreeMap<>();
        List<PaperQuestionCandidate> examCandidates = candidates(assignmentId, KIND_EXAM_PAPER);
        List<PaperQuestionCandidate> keyCandidates = candidates(assignmentId, KIND_ANSWER_KEY);
        examCandidates.stream().filter(candidate -> candidate.questionCode() != null)
            .forEach(candidate -> examByCode.putIfAbsent(candidate.questionCode(), candidate));
        keyCandidates.stream().filter(candidate -> candidate.questionCode() != null)
            .forEach(candidate -> keyByCode.putIfAbsent(candidate.questionCode(), candidate));

        for (PaperQuestionCandidate candidate : keyCandidates) {
            PaperQuestionCandidate matched = examByCode.get(candidate.questionCode());
            // 答案卷多出一道空白卷没有的题：通常是空白卷漏检，学生会因此少做一道题。
            applyMatch(candidate, matched == null ? null : matched.questionCode(),
                WARN_ANSWER_KEY_UNMATCHED, matched != null);
        }
        for (PaperQuestionCandidate candidate : examCandidates) {
            boolean hasAnswer = candidate.questionCode() != null
                && keyByCode.containsKey(candidate.questionCode());
            // 空白卷有题而答案卷没对应答案：AI 评分会缺少依据，教师需要自己填标准答案。
            applyMatch(candidate, null, WARN_ANSWER_KEY_MISSING, hasAnswer);
        }
    }

    private void applyMatch(PaperQuestionCandidate candidate, String matchedCode,
                            String warningCode, boolean matched) {
        LinkedHashSet<String> warnings = new LinkedHashSet<>(candidate.warnings());
        if (matched) {
            warnings.remove(warningCode);
        } else {
            warnings.add(warningCode);
        }
        if (java.util.Objects.equals(candidate.matchedQuestionCode(), matchedCode)
            && warnings.equals(new LinkedHashSet<>(candidate.warnings()))) {
            return;
        }
        jdbc.sql("""
            update paper_question_candidate
            set matched_question_code = :matchedCode, warnings = :warnings, updated_at = current_timestamp(3)
            where id = :id
            """)
            .param("matchedCode", matchedCode)
            .param("warnings", writeJson(List.copyOf(warnings)))
            .param("id", candidate.id())
            .update();
    }

    private OcrResult readOcrResult(long documentId) {
        String raw = jdbc.sql("""
                select raw_output from ocr_task
                where document_id = :documentId and raw_output is not null
                order by id desc limit 1
                """)
            .param("documentId", documentId)
            .query(String.class)
            .optional()
            .orElseThrow(() -> new DomainException("OCR_RESULT_MISSING",
                "还没有可用的识别结果，请先完成识别", HttpStatus.CONFLICT));
        try {
            OcrResult result = objectMapper.readValue(raw, OcrResult.class);
            result.requireValid();
            return result;
        } catch (JacksonException exception) {
            throw new DomainException("OCR_RESULT_UNREADABLE", "识别结果无法解析",
                HttpStatus.INTERNAL_SERVER_ERROR, exception);
        }
    }

    // ---------- 查询 ----------

    private ImportRow requireImport(long teacherId, long assignmentId) {
        return jdbc.sql("""
                select a.id, a.class_id, a.title, a.status
                from assignment a
                where a.id = :assignmentId and a.teacher_id = :teacherId and a.deleted_at is null
                """)
            .param("assignmentId", assignmentId)
            .param("teacherId", teacherId)
            .query((rs, rowNum) -> new ImportRow(rs.getLong("id"), rs.getLong("class_id"),
                rs.getString("title"), AssignmentStatus.parse(rs.getString("status"))))
            .optional()
            .orElseThrow(() -> new DomainException("ASSIGNMENT_NOT_FOUND", "作业不存在", HttpStatus.NOT_FOUND));
    }

    private void ensureNotConfirmed(long assignmentId) {
        Integer confirmed = jdbc.sql("""
                select count(*) from document_upload
                where assignment_id = :assignmentId and status = 'CONFIRMED' and deleted_at is null
                """)
            .param("assignmentId", assignmentId)
            .query(Integer.class)
            .single();
        if (confirmed != null && confirmed > 0) {
            throw new DomainException("PAPER_IMPORT_CONFIRMED",
                "这份整卷已经确认入库，不能再修改", HttpStatus.CONFLICT);
        }
    }

    private Optional<DocumentRow> findDocument(long assignmentId, String documentKind) {
        return documents(assignmentId).stream()
            .filter(document -> document.documentKind().equals(documentKind))
            .findFirst();
    }

    private List<DocumentRow> documents(long assignmentId) {
        return jdbc.sql("""
                select du.id, du.document_kind, du.status, du.page_count, du.failure_reason_code,
                       du.teacher_id, du.original_file_id, sf.storage_key, sf.mime_type,
                       (select t.status from ocr_task t where t.document_id = du.id
                          order by t.id desc limit 1) as ocr_status,
                       (select t.failure_code from ocr_task t where t.document_id = du.id
                          order by t.id desc limit 1) as ocr_failure_code
                from document_upload du
                join stored_file sf on sf.id = du.original_file_id
                where du.assignment_id = :assignmentId and du.deleted_at is null
                  and du.document_kind in ('EXAM_PAPER', 'ANSWER_KEY')
                order by du.document_kind, du.id
                """)
            .param("assignmentId", assignmentId)
            .query((rs, rowNum) -> new DocumentRow(rs.getLong("id"), rs.getString("document_kind"),
                rs.getString("status"), rs.getInt("page_count"), rs.getString("failure_reason_code"),
                rs.getString("ocr_status"), rs.getString("ocr_failure_code"),
                rs.getLong("teacher_id"), rs.getLong("original_file_id"),
                rs.getString("storage_key"), rs.getString("mime_type")))
            .list();
    }

    private String statusOf(long documentId) {
        return jdbc.sql("select status from document_upload where id = :id")
            .param("id", documentId)
            .query(String.class)
            .single();
    }

    private List<PageRow> pages(long documentId) {
        return jdbc.sql("""
                select id, page_no, page_file_id, thumbnail_file_id, original_width, original_height
                from document_page where document_id = :documentId order by page_no
                """)
            .param("documentId", documentId)
            .query((rs, rowNum) -> new PageRow(rs.getLong("id"), rs.getInt("page_no"),
                rs.getLong("page_file_id"), (Long) rs.getObject("thumbnail_file_id"),
                (Integer) rs.getObject("original_width"), (Integer) rs.getObject("original_height")))
            .list();
    }

    private List<RegionRow> regions(long pageId) {
        return jdbc.sql(REGION_SELECT + " where dr.page_id = :pageId order by dr.id")
            .param("pageId", pageId)
            .query(PaperImportService::toRegionRow)
            .list();
    }

    /**
     * 按 id 取区域，但必须属于这次导入。
     *
     * <p>区域 id 全局自增，只按 id 取会让别人导入里的区域被当成自己的题图来源；
     * 校验必须落在 SQL 里，否则"记得在内存里比对一下 assignment_id"迟早会被漏掉。
     */
    private List<RegionRow> requireRegions(long assignmentId, List<Long> regionIds) {
        List<RegionRow> found = new ArrayList<>();
        for (Long regionId : regionIds) {
            jdbc.sql(REGION_SELECT + """
                     join document_upload du on du.id = dp.document_id
                     where dr.id = :regionId and du.assignment_id = :assignmentId and du.deleted_at is null
                    """)
                .param("regionId", regionId)
                .param("assignmentId", assignmentId)
                .query(PaperImportService::toRegionRow)
                .optional()
                .ifPresent(found::add);
        }
        return found;
    }

    private static RegionRow toRegionRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        // 坐标列是 decimal，getObject 会给出 BigDecimal；用 getDouble + wasNull 才能同时拿到
        // "没有值"与"值本身"两种情形，直接强转会在这里抛 ClassCastException。
        // wasNull 只对"最近一次读取"有效，所以必须在读完 confidence 之后立刻取值。
        double rawConfidence = rs.getDouble("confidence");
        Double confidence = rs.wasNull() ? null : rawConfidence;
        return new RegionRow(rs.getLong("id"), rs.getLong("page_id"), rs.getLong("page_file_id"),
            rs.getString("region_type"), rs.getDouble("x"), rs.getDouble("y"),
            rs.getDouble("width"), rs.getDouble("height"), rs.getString("ocr_text"),
            rs.getString("ocr_latex"), confidence,
            (Long) rs.getObject("crop_file_id"), rs.getString("review_status"));
    }

    private RegionRow requireRegionRow(long regionId) {
        return jdbc.sql(REGION_SELECT + " where dr.id = :regionId")
            .param("regionId", regionId)
            .query(PaperImportService::toRegionRow)
            .optional()
            .orElseThrow(() -> new DomainException("REGION_NOT_FOUND", "区域不存在", HttpStatus.NOT_FOUND));
    }

    private RegionRow requireRegion(long assignmentId, long regionId) {
        return jdbc.sql(REGION_SELECT + """
                 join document_upload du on du.id = dp.document_id
                 where dr.id = :regionId and du.assignment_id = :assignmentId and du.deleted_at is null
                """)
            .param("regionId", regionId)
            .param("assignmentId", assignmentId)
            .query(PaperImportService::toRegionRow)
            .optional()
            .orElseThrow(() -> new DomainException("REGION_NOT_FOUND", "区域不存在", HttpStatus.NOT_FOUND));
    }

    private List<PaperQuestionCandidate> candidates(long assignmentId, String documentKind) {
        String sql = """
            select id, document_id, document_kind, order_no, question_code, question_type, content,
                   standard_answer, accepted_answers, rubric_items, total_score, difficulty,
                   primary_knowledge_point_id, source_region_ids, asset_region_ids, matched_question_code,
                   confidence, warnings, review_status, version
            from paper_question_candidate
            where assignment_id = :assignmentId
            """ + (documentKind == null ? "" : " and document_kind = :documentKind") + " order by document_kind, order_no";
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("assignmentId", assignmentId);
        if (documentKind != null) {
            spec = spec.param("documentKind", documentKind);
        }
        return spec.query((rs, rowNum) -> toCandidate(rs, rowNum)).list();
    }

    private PaperQuestionCandidate requireCandidate(long assignmentId, long candidateId) {
        return jdbc.sql("""
                select id, document_id, document_kind, order_no, question_code, question_type, content,
                       standard_answer, accepted_answers, rubric_items, total_score, difficulty,
                       primary_knowledge_point_id, source_region_ids, asset_region_ids, matched_question_code,
                       confidence, warnings, review_status, version
                from paper_question_candidate
                where id = :id and assignment_id = :assignmentId
                """)
            .param("id", candidateId)
            .param("assignmentId", assignmentId)
            .query((rs, rowNum) -> toCandidate(rs, rowNum))
            .optional()
            .orElseThrow(() -> new DomainException("PAPER_CANDIDATE_NOT_FOUND",
                "候选题不存在", HttpStatus.NOT_FOUND));
    }

    private PaperQuestionCandidate toCandidate(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        // confidence 是 decimal(5,4)，getObject 返回 BigDecimal；直接强转成 Double 会抛
        // ClassCastException，而异常会被包成"请求处理失败"，看不出是哪个字段的问题。
        double rawConfidence = rs.getDouble("confidence");
        Double confidence = rs.wasNull() ? null : rawConfidence;
        String questionType = rs.getString("question_type");
        String difficulty = rs.getString("difficulty");
        return new PaperQuestionCandidate(rs.getLong("id"), rs.getLong("document_id"),
            rs.getString("document_kind"), rs.getInt("order_no"), rs.getString("question_code"),
            questionType == null ? null : QuestionType.valueOf(questionType), rs.getString("content"),
            rs.getString("standard_answer"), read(rs.getString("accepted_answers"), STRING_LIST),
            read(rs.getString("rubric_items"), RUBRIC_LIST), (Integer) rs.getObject("total_score"),
            difficulty == null ? null : QuestionDifficulty.valueOf(difficulty),
            (Long) rs.getObject("primary_knowledge_point_id"),
            read(rs.getString("source_region_ids"), LONG_LIST), read(rs.getString("asset_region_ids"), LONG_LIST),
            rs.getString("matched_question_code"), confidence,
            read(rs.getString("warnings"), STRING_LIST), rs.getString("review_status"), rs.getInt("version"));
    }

    private static List<String> aggregateWarnings(List<DocumentRow> documents,
                                                  List<PaperQuestionCandidate> candidates) {
        LinkedHashSet<String> warnings = new LinkedHashSet<>();
        for (DocumentRow document : documents) {
            if (document.failureCode() != null) {
                warnings.add(document.documentKind() + ":" + document.failureCode());
            }
        }
        for (PaperQuestionCandidate candidate : candidates) {
            if (!REVIEW_CONFIRMED.equals(candidate.reviewStatus())) {
                warnings.addAll(candidate.warnings());
            }
        }
        return List.copyOf(warnings);
    }

    private byte[] readStoredBytes(long fileId) {
        String key = jdbc.sql("select storage_key from stored_file where id = :id")
            .param("id", fileId)
            .query(String.class)
            .optional()
            .orElseThrow(() -> new DomainException("FILE_NOT_FOUND", "文件不存在", HttpStatus.NOT_FOUND));
        try (var input = storage.open(key)) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new DomainException("STORAGE_READ_FAILED", "文件内容暂时不可用",
                HttpStatus.SERVICE_UNAVAILABLE, exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("候选数据无法序列化", exception);
        }
    }

    private <T> List<T> read(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException exception) {
            throw new IllegalStateException("候选数据损坏", exception);
        }
    }

    private static String requireKind(String kind) {
        String normalized = kind == null ? "" : kind.trim().toUpperCase(java.util.Locale.ROOT);
        if (!DOCUMENT_KINDS.contains(normalized)) {
            throw new DomainException("PAPER_IMPORT_KIND_INVALID",
                "只支持 EXAM_PAPER（空白试卷）与 ANSWER_KEY（参考答案）");
        }
        return normalized;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static <T> T pick(T candidate, T current) {
        return candidate == null ? current : candidate;
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /** 创建导入的请求体。 */
    public record CreateCommand(@Positive long classId, @NotBlank @Size(max = 128) String title) {}

    private record ImportRow(long assignmentId, long classId, String title, AssignmentStatus status) {}

    private record DocumentRow(long documentId, String documentKind, String status, int pageCount,
                               String failureCode, String ocrStatus, String ocrFailureCode,
                               long teacherId, long originalFileId,
                               String storageKey, String mimeType) {}

    private record PageRow(long pageId, int pageNo, long pageFileId, Long thumbnailFileId,
                           Integer width, Integer height) {}

    private record RegionRow(long regionId, long pageId, long pageFileId, String regionType,
                             double x, double y, double width, double height, String ocrText,
                             String ocrLatex, Double confidence, Long cropFileId, String reviewStatus) {}
}
