package com.homework.analysis.submission;

import com.homework.analysis.document.FileStorage;
import com.homework.analysis.document.ImageTransforms;
import com.homework.analysis.document.OcrRegionWriter;
import com.homework.analysis.document.OcrRegionWriter.WrittenRegion;
import com.homework.analysis.document.StoredFileService;
import com.homework.analysis.ocr.OcrRequest;
import com.homework.analysis.ocr.OcrResult;
import com.homework.analysis.ocr.OcrTaskWorker;
import com.homework.analysis.ocr.OcrTemplateProvider;
import com.homework.analysis.paper.PaperImportService;
import com.homework.analysis.question.QuestionService;
import com.homework.analysis.question.QuestionView;
import com.homework.analysis.shared.error.DomainException;
import com.homework.analysis.shared.jdbc.GeneratedKeys;
import com.homework.analysis.submission.SubmissionVersionService.VersionRef;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 学生答卷的答案抽取：把识别结果落成"每道题一条候选"，供教师校对。
 *
 * <p>与整卷导入（{@code PaperImportService}）的三条铁律完全一致，因为它们说的是同一件事：
 * <ol>
 *   <li><b>识别只产生候选。</b>这里不写 {@code student_answer}、不写任何成绩。那些只能由
 *       {@code SubmissionConfirmationService} 在教师确认后一次性写入。</li>
 *   <li><b>区域一个都不删。</b>识别出多少块就落多少块；没被任何题框认领的区域留在界面上等教师处理，
 *       而不是消失。丢掉区域等于丢掉唯一的核对依据。</li>
 *   <li><b>越权与不存在同返回 404。</b>归属校验只走"这一版挂在我名下的作业上吗"。</li>
 * </ol>
 *
 * <p>与整卷导入最大的不同是**归属依据**：空白卷上的题号是印上去的，从文字里就能读出来
 * （整卷导入的做法）；学生答卷上的作答没有可读的题号锚点，只能靠"落在哪个题框里"。
 * 所以这里要模板几何，而模板几何在识别阶段就已经通过 {@code OcrTemplateProvider} 送出去了——
 * 归属与配准用的是同一份题框，两边不一致会让"配准成功但一块区域都归不了位"。
 *
 * <p>置信度门槛与整卷导入共用 {@link PaperImportService#LOW_CONFIDENCE_THRESHOLD}：
 * 同一个识别引擎，"多不确定要提醒人看"只能有一个答案。
 */
@Service
public class AnswerExtractionService {

    private static final Logger log = LoggerFactory.getLogger(AnswerExtractionService.class);

    /** OCR 任务的幂等键里的处理版本；与 {@code SubmissionVersionService} 保持一致。 */
    private static final int PROCESSING_VERSION = 1;

    /** 与 {@code PaperImportService} 同一个门槛，见类注释。 */
    private static final double LOW_CONFIDENCE_THRESHOLD = PaperImportService.LOW_CONFIDENCE_THRESHOLD;

    /**
     * 两块题框各压住同一块区域到这个比例以上时，判为归属不确定。
     *
     * <p>0.3 是个明确的取舍：区域横跨两题（学生把后一题的解答写在前一题的空白里）时，
     * 两块框各占三成以上，选哪个都是猜；而正常作答落在自己那道题的框里时，
     * 相邻题的框顶多压住一个边角。猜错的代价是把一道题的解答算到另一道题头上，
     * 学生看到的只会是自己分数变低——所以宁可提示教师选一次。
     */
    private static final double AMBIGUITY_MIN_OVERLAP = 0.3;

    static final String REVIEW_PENDING = "PENDING";
    static final String REVIEW_CONFIRMED = "CONFIRMED";

    /**
     * 允许的校对状态。
     *
     * <p>没有 {@code REJECTED}（整卷导入有三值）：答卷里"这道题的作答分错了"总有更好的修法——
     * 改派到正确的题（{@link AnswerCorrectionCommand#questionId()}）。允许否掉一条候选会造出
     * "某道题没有候选"的状态，而"每道题恰好一条候选"正是确认阶段要检查的不变量。
     */
    private static final Set<String> ALLOWED_REVIEW_STATUSES = Set.of(REVIEW_PENDING, REVIEW_CONFIRMED);

    private static final TypeReference<List<Long>> LONG_LIST = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    /** 整份答卷级别的警告。元素是 {@code {"code","detail"}}，与 {@code AnswerReviewView.WarningView} 同形。 */
    private static final TypeReference<List<AnswerReviewView.WarningView>> WARNING_LIST = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final SubmissionVersionService versions;
    private final QuestionService questions;
    private final OcrTaskWorker ocrWorker;
    private final OcrTemplateProvider templateProvider;
    private final OcrRegionWriter regionWriter;
    private final StoredFileService files;
    private final FileStorage storage;
    private final ObjectMapper objectMapper;

    AnswerExtractionService(JdbcClient jdbc, SubmissionVersionService versions, QuestionService questions,
                            OcrTaskWorker ocrWorker, OcrTemplateProvider templateProvider,
                            OcrRegionWriter regionWriter, StoredFileService files, FileStorage storage,
                            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.versions = versions;
        this.questions = questions;
        this.ocrWorker = ocrWorker;
        this.templateProvider = templateProvider;
        this.regionWriter = regionWriter;
        this.files = files;
        this.storage = storage;
        this.objectMapper = objectMapper;
    }

    // ---------- 对外接口 ----------

    /**
     * 跑识别并物化答案候选。
     *
     * <p>与整卷导入一样是教师触发的一次性动作（项目里没有排空任务的调度器）：
     * 先重试失败的识别，再让 {@link OcrTaskWorker} 处理这一版所有文档的待办任务，
     * 最后在还没有候选时物化。
     *
     * <p>幂等性有两层：{@code ocr_task} 靠 {@code (document_id, processing_version)} 唯一键保证
     * 不会重复调用引擎；候选只在尚未生成时物化，因此重复点"识别"不会覆盖教师已经改过的内容。
     * 已确认入库的版本直接拒绝（{@link VersionRef#requireAnswerReviewable()}）——重新物化会换掉
     * 候选主键，而教师刚改完的那一条会凭空消失；要重来只能退回重交。
     */
    @Transactional
    public AnswerReviewView process(long teacherId, long versionId) {
        VersionRef version = versions.requireForTeacher(teacherId, versionId);
        version.requireAnswerReviewable();
        List<SubmissionPageRow> pages = submissionPages(versionId);
        if (pages.isEmpty()) {
            // 没有页面就什么都识别不了。这不是"等一会儿再来"，学生必须先把页面传上来。
            throw new DomainException("SUBMISSION_HAS_NO_PAGE",
                "这一版还没有页面，无法识别", HttpStatus.CONFLICT);
        }
        versions.resetFailedOcrTasks(versionId);
        for (long documentId : pages.stream().map(SubmissionPageRow::documentId).distinct().toList()) {
            ocrWorker.processOne(teacherId, documentId);
        }
        if (candidateCount(versionId) == 0) {
            materialize(teacherId, version, pages);
        }
        return get(teacherId, versionId);
    }

    /**
     * 校对视图。不触发识别，也不改任何东西。
     *
     * <p>区域是**按页**列出来的，归属关系反查在每一块区域上（{@code candidateId} 为空即未归属）。
     * 不能反过来"只列候选名下的区域"：那样未归属的区域就看不见了，而它们恰恰是教师最需要处理的
     * 那一部分——识别出来却没归到任何题上的作答，只有在页面上才找得到。
     */
    public AnswerReviewView get(long teacherId, long versionId) {
        VersionRef version = versions.requireForTeacher(teacherId, versionId);
        List<SubmissionPageRow> pages = submissionPages(versionId);
        List<CandidateRow> candidates = candidates(versionId);

        Map<Long, Long> candidateByRegion = new LinkedHashMap<>();
        for (CandidateRow candidate : candidates) {
            read(candidate.sourceRegionIds(), LONG_LIST)
                .forEach(regionId -> candidateByRegion.put(regionId, candidate.candidateId()));
        }
        Map<PageKey, List<RegionRow>> regionsByPage = regions(pages);
        Map<PageKey, PageAlignment> alignments = pageAlignments(pages);

        Map<Long, AnswerReviewView.AnswerRegionView> regionViews = new LinkedHashMap<>();
        List<AnswerReviewView.AnswerPageView> pageViews = new ArrayList<>();
        for (SubmissionPageRow page : pages) {
            PageKey key = new PageKey(page.documentId(), page.documentPageNo());
            List<AnswerReviewView.AnswerRegionView> pageRegions = new ArrayList<>();
            for (RegionRow region : regionsByPage.getOrDefault(key, List.of())) {
                AnswerReviewView.AnswerRegionView view = new AnswerReviewView.AnswerRegionView(
                    region.regionId(), page.pageNo(), region.regionType(), region.x(), region.y(),
                    region.width(), region.height(), region.ocrText(), region.ocrLatex(),
                    region.confidence(), region.cropFileId(), candidateByRegion.get(region.regionId()));
                regionViews.put(region.regionId(), view);
                pageRegions.add(view);
            }
            PageAlignment alignment = alignments.getOrDefault(key, PageAlignment.NONE);
            pageViews.add(new AnswerReviewView.AnswerPageView(page.submissionPageId(), page.pageNo(),
                page.documentId(), page.documentPageNo(), page.fileName(), page.pageFileId(),
                page.rotatedFileId(), page.width(), page.height(), alignment.templatePageNo(),
                alignment.confidence(), List.copyOf(pageRegions)));
        }

        List<AnswerReviewView.AnswerCandidateView> candidateViews = new ArrayList<>();
        for (CandidateRow candidate : candidates) {
            // 区域在这次读里已经全部建好了，候选名下直接引用它们：同一块区域在老师和这道题
            // 两侧必须是同一个描述，各建一份迟早会出现两边字段不一致。
            List<AnswerReviewView.AnswerRegionView> candidateRegions = read(candidate.sourceRegionIds(), LONG_LIST)
                .stream()
                .map(regionViews::get)
                .filter(region -> region != null)
                .toList();
            candidateViews.add(new AnswerReviewView.AnswerCandidateView(candidate.candidateId(),
                candidate.questionId(), candidate.questionCode(), candidate.questionOrder(),
                candidate.orderNo(), candidate.answerText(), candidate.answerLatex(), candidate.blank(),
                candidate.confidence(), candidate.reviewStatus(), candidate.version(), candidateRegions,
                read(candidate.warnings(), STRING_LIST)));
        }
        return new AnswerReviewView(versionId, version.assignmentId(), version.studentId(),
            studentName(version.studentId()), version.versionNo(), version.status(),
            version.status().answersConfirmed(),
            read(extractionWarnings(versionId), WARNING_LIST), pageViews, candidateViews);
    }

    /**
     * 一条候选名下的区域 id。
     *
     * <p>给确认入库阶段用（它要照着这份清单去找答案图）。之所以在这里开一个口子而不是让它自己解，
     * 是因为"{@code source_region_ids} 是一串 JSON 数字"这个事实只该有一处定义：格式一变，
     * 两处读法不一致的症状是"确认后的答案少了半张图"。
     */
    List<Long> regionIdsOf(String sourceRegionIds) {
        return read(sourceRegionIds, LONG_LIST);
    }

    /**
     * 标准答案参考面板。
     *
     * <p>单独一个接口、单独一次请求，而不是挂在 {@link #get} 的响应里：教师对着标准答案看学生写的字，
     * 会不自觉地"看出"那个答案，判分就不再独立。要看得自己点开，这是一个刻意保留的摩擦。
     *
     * <p>返回的就是题库里的 {@code QuestionView}，沿用它与它的读取实现：参考面板上要显示的
     * 每一栏（题干、标准答案、可接受答案、评分项、分值）在那里已经有一份定义，
     * 在这里另写一份查询，只会让"题库里的题长什么样"出现第二种说法。
     *
     * <p>代价是逐题查询（一道题一次）。它只发生在教师点开参考面板那一刻，
     * 而一份作业的题目数是几十的量级——为此引入一个批量查询，换来的是两份字段映射要同步维护。
     */
    public List<QuestionView> referenceAnswers(long teacherId, long versionId) {
        VersionRef version = versions.requireForTeacher(teacherId, versionId);
        List<Long> questionIds = jdbc.sql("""
                select question_id from assignment_question
                where assignment_id = :assignmentId order by question_order
                """)
            .param("assignmentId", version.assignmentId())
            .query(Long.class)
            .list();
        List<QuestionView> answers = new ArrayList<>();
        for (Long questionId : questionIds) {
            answers.add(questions.requireOwned(teacherId, questionId));
        }
        return answers;
    }

    /**
     * 教师改一条候选：改题目映射、改识别出来的文字、标为空白、确认。
     *
     * <p>乐观锁：{@code version} 必须与库里一致，否则 409。缺版本号是 400，**不当成版本 0**——
     * 刚物化完的候选版本正好是 0，把漏传当成 0 会让一次本该被拦住的请求静默通过。
     *
     * <p>审计只记"改了哪些字段"，不记改前改后的内容：那是一道题的学生作答，
     * 而审计行会被很多眼睛看到；内容由候选行本身保存，审计表不该变成第二个答案副本。
     */
    @Transactional
    public AnswerReviewView correct(long teacherId, long versionId, long candidateId,
                                    AnswerCorrectionCommand command) {
        VersionRef version = versions.requireForTeacher(teacherId, versionId);
        // "已经确认入库"这一条也在里面：答案一旦入库，候选就只读了。
        version.requireAnswerReviewable();
        Integer expectedVersion = command.version();
        if (expectedVersion == null) {
            throw new DomainException("ANSWER_CANDIDATE_VERSION_REQUIRED",
                "修改答案必须带上当前版本号", HttpStatus.BAD_REQUEST);
        }
        CandidateRow current = requireCandidate(versionId, candidateId);
        if (current.version() != expectedVersion) {
            throw new DomainException("OCR_REVIEW_CONFLICT",
                "这道题已被其他操作修改，请刷新后重试", HttpStatus.CONFLICT);
        }
        List<String> changedFields = new ArrayList<>();
        Long questionId = command.questionId() == null ? current.questionId() : command.questionId();
        CandidateRow counterpart = null;
        if (command.questionId() != null && command.questionId() != current.questionId()) {
            requireAssignmentQuestion(version.assignmentId(), command.questionId());
            counterpart = counterpartOf(versionId, candidateId, command.questionId());
            changedFields.add("questionId");
        }
        String answerText = command.answerText() == null ? current.answerText() : command.answerText();
        if (command.answerText() != null) {
            changedFields.add("answerText");
        }
        String answerLatex = command.answerLatex() == null ? current.answerLatex() : command.answerLatex();
        if (command.answerLatex() != null) {
            changedFields.add("answerLatex");
        }
        boolean blank = command.blank() == null ? current.blank() : command.blank();
        if (command.blank() != null) {
            changedFields.add("blank");
        }
        String reviewStatus = command.reviewStatus() == null
            ? current.reviewStatus() : requireReviewStatus(command.reviewStatus());
        if (command.reviewStatus() != null) {
            changedFields.add("reviewStatus");
        }
        if (Boolean.TRUE.equals(command.crop())) {
            cropCandidate(teacherId, current);
            changedFields.add("crop");
        }
        if (changedFields.isEmpty()) {
            // 空请求写一条什么都看不出来的审计行，只会让审计表变噪声；而且要教师明确表达意图。
            throw new DomainException("ANSWER_CORRECTION_EMPTY",
                "这次请求没有要修改的内容", HttpStatus.BAD_REQUEST);
        }
        if (counterpart == null) {
            // 目标题上还没有候选（教师在识别之后往作业里加过题）：这一条直接搬过去。
            // 顺序跟着题目走——order_no 决定界面顺序，留在原位就与作业题目顺序对不上了。
            editCandidate(candidateId, expectedVersion, answerText, answerLatex, blank, reviewStatus,
                questionId, questionOrder(version.assignmentId(), questionId));
        } else {
            editCandidate(candidateId, expectedVersion, answerText, answerLatex, blank, reviewStatus,
                current.questionId(), current.orderNo());
            swapContentWith(versionId, candidateId, counterpart);
        }
        versions.auditAnswerCorrection(version, teacherId,
            correctionDetail(current, counterpart, changedFields));
        return get(teacherId, versionId);
    }

    /**
     * 写回这一条候选：要改的字段连同它属于哪道题、排第几。
     *
     * <p>{@code questionId} 与 {@code orderNo} 一律显式写：改派时它们要跟着变，
     * 只改文字时传的是原值（一次幂等的自赋值），省掉"改哪些列"的第二种拼 SQL 方式。
     */
    private void editCandidate(long candidateId, int expectedVersion, String answerText, String answerLatex,
                               boolean blank, String reviewStatus, long questionId, int orderNo) {
        jdbc.sql("""
                update submission_answer_candidate
                set question_id = :questionId, order_no = :orderNo, answer_text = :answerText,
                    answer_latex = :answerLatex, blank = :blank, review_status = :reviewStatus,
                    version = version + 1, updated_at = current_timestamp(3)
                where id = :id and version = :expectedVersion
                """)
            .param("questionId", questionId)
            .param("orderNo", orderNo)
            .param("answerText", answerText)
            .param("answerLatex", answerLatex)
            .param("blank", blank)
            .param("reviewStatus", reviewStatus)
            .param("id", candidateId)
            .param("expectedVersion", expectedVersion)
            .update();
    }

    /**
     * 互换两行的作答内容。
     *
     * <p><b>为什么换内容而不是换题号。</b>两条路得到的结果一样（第 1 题拿到原本第 2 题的作答，
     * 第 2 题拿到原本第 1 题的），但换题号撞不过 {@code uk_answer_candidate_question}：
     * 唯一键是逐行检查的，"先把 A 改到第 2 题、再把 B 改到第 1 题"里总有一瞬间两道题同号。
     * 换内容则是两次互不相干的行内更新，两边库（MySQL 与 H2）行为一致；
     * 而且它更贴教师那句话本身——"这块作答属于第 2 题"，题是不动的。
     *
     * <p>区域、裁剪图、置信度、警告都跟着内容走：它们描述的就是那块作答。
     */
    private void swapContentWith(long versionId, long editedId, CandidateRow counterpart) {
        CandidateRow edited = requireCandidate(versionId, editedId);
        copyContent(edited.candidateId(), edited.version(), counterpart);
        copyContent(counterpart.candidateId(), counterpart.version(), edited);
    }

    /**
     * 把 {@code source} 那一行的作答内容写进目标行。
     *
     * <p>两边都落回 {@code PENDING}：教师确认过的是一条"第 N 题 + 这块作答"的配对，
     * 互换之后两行都换掉了一半，没有哪一边还是他看过的那一对。让他重新确认一遍，
     * 比让机器把"我核对过"这句话挪到一个他没看过的组合上安全。
     *
     * <p>版本号加一：两行都被这次操作改过，教师手上那份旧副本不能再写回去。
     */
    private void copyContent(long targetId, int expectedVersion, CandidateRow source) {
        jdbc.sql("""
                update submission_answer_candidate
                set answer_text = :answerText, answer_latex = :answerLatex, source_region_ids = :sourceRegionIds,
                    confidence = :confidence, blank = :blank, warnings = :warnings,
                    review_status = 'PENDING', version = version + 1, updated_at = current_timestamp(3)
                where id = :id and version = :expectedVersion
                """)
            .param("answerText", source.answerText())
            .param("answerLatex", source.answerLatex())
            .param("sourceRegionIds", source.sourceRegionIds())
            .param("confidence", source.confidence())
            .param("blank", source.blank())
            .param("warnings", source.warnings())
            .param("id", targetId)
            .param("expectedVersion", expectedVersion)
            .update();
    }

    /**
     * 审计里那句话。
     *
     * <p>互换要说清"和谁换了"：审计行是排查时唯一的线索，只写"改了 questionId"，
     * 读的人还得自己去比对两次改动才能知道哪两道题的作答被调了个个儿。
     */
    private static String correctionDetail(CandidateRow current, CandidateRow counterpart,
                                          List<String> changedFields) {
        String fields = String.join("、", changedFields);
        if (counterpart == null) {
            return orderLabel(current) + "：改了 " + fields;
        }
        return orderLabel(current) + " ↔ " + orderLabel(counterpart) + "：互换了题目映射（" + fields + "）";
    }

    private static String orderLabel(CandidateRow candidate) {
        return candidate.orderNo() == null ? "这一条" : "第 " + candidate.orderNo() + " 题";
    }

    // ---------- 守卫 ----------

    private static String requireReviewStatus(String raw) {
        String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!ALLOWED_REVIEW_STATUSES.contains(normalized)) {
            throw new DomainException("ANSWER_REVIEW_STATUS_INVALID", "无法识别的校对状态：" + raw);
        }
        return normalized;
    }

    /** 这道题必须真的挂在这份作业上：改派到别的作业的题目，会让答卷出现一道不是这份作业的题。 */
    private void requireAssignmentQuestion(long assignmentId, long questionId) {
        Integer found = jdbc.sql("""
                select count(*) from assignment_question
                where assignment_id = :assignmentId and question_id = :questionId
                """)
            .param("assignmentId", assignmentId)
            .param("questionId", questionId)
            .query(Integer.class)
            .single();
        if (found == null || found == 0) {
            throw new DomainException("ANSWER_QUESTION_NOT_IN_ASSIGNMENT",
                "这道题不属于这份作业", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 目标题上现在那一条候选（要被换到本题来的那条）。
     *
     * <p><b>为什么是"互换"而不是"移过去"。</b>每道作业题恒有且只有一条候选
     * （{@code uk_answer_candidate_question} 保证），所以"改派到第 2 题"这句话里，
     * 第 2 题那条候选必须同时有个去处——它换到本题来。早先的写法要求目标题"空着"，
     * 而那在结构上永远不会发生：这条路径等于永远 409，教师看到"改不了"却不知道
     * 该怎么做。所以这里没有"目标题必须空"这道校验，直接按互换处理。
     *
     * <p>目标题确实空着（教师中途往作业里加过题）时返回空，此时就是一次普通的移动。
     *
     * <p>对面已经校对确认过就拒绝：互换会改变"这道题对不对得上那块作答"这个判断的
     * 前提，而那是教师做出的判断，不能被他自己的下一次点击顺手推翻。要换就先把它改回待校对——
     * 多一步是刻意的，和"确认过的评分不能被退回静默作废"是同一个道理。
     */
    private CandidateRow counterpartOf(long versionId, long candidateId, long questionId) {
        CandidateRow counterpart = candidates(versionId).stream()
            .filter(candidate -> candidate.candidateId() != candidateId
                && candidate.questionId() == questionId)
            .findFirst()
            .orElse(null);
        if (counterpart != null && REVIEW_CONFIRMED.equals(counterpart.reviewStatus())) {
            throw new DomainException("ANSWER_COUNTERPART_CONFIRMED",
                orderLabel(counterpart) + "已经校对确认过了，改派前请先把它改回待校对",
                HttpStatus.CONFLICT);
        }
        return counterpart;
    }

    // ---------- 物化 ----------

    /**
     * 把识别结果落成"每道题一条候选"。
     *
     * <p>顺序与整卷导入一致：先落页面与区域（它们是教师核对像素的唯一依据），再落候选
     * （候选只是区域的一种分组视图）。反过来先建候选会出现"候选指向不存在的框"的中间态，
     * 而那种中间态一旦被读到，教师就会看到一道没有作答的题。
     *
     * <p>每道作业题都会得到一条候选，包括学生没作答的：这样"每道题恰好一条候选"是结构性质，
     * 而不是确认时才发现缺了几道。空白候选带 {@link AnswerWarning#ANSWER_BLANK}，
     * 教师要么确认"确实没写"，要么改派到别的题。
     */
    private void materialize(long teacherId, VersionRef version, List<SubmissionPageRow> pages) {
        List<QuestionRow> questions = assignmentQuestions(version.assignmentId());
        Map<String, Long> questionIdByCode = new LinkedHashMap<>();
        for (QuestionRow question : questions) {
            if (question.questionCode() != null) {
                questionIdByCode.putIfAbsent(question.questionCode(), question.questionId());
            }
        }

        // 一份上传可能对应多页（PDF），所以按文档分组建页、落区域。
        Map<Long, OcrOutcome> outcomes = new LinkedHashMap<>();
        Map<PageKey, List<WrittenRegion>> regionsByPage = new LinkedHashMap<>();
        Map<PageKey, Integer> templatePageByPage = new LinkedHashMap<>();
        Map<PageKey, Long> pageFileByPage = new LinkedHashMap<>();
        pages.forEach(page -> {
            pageFileByPage.put(new PageKey(page.documentId(), page.documentPageNo()), page.pageFileId());
        });
        for (long documentId : distinctDocuments(pages)) {
            OcrOutcome outcome = ocrOutcome(documentId);
            outcomes.put(documentId, outcome);
            if (outcome.result() == null) {
                continue;
            }
            Map<Integer, Long> pageIds = insertPages(teacherId, documentId, outcome.result(), pageFileByPage);
            for (WrittenRegion region : regionWriter.write(pageIds, outcome.result())) {
                regionsByPage.computeIfAbsent(new PageKey(documentId, region.pageNo()), key -> new ArrayList<>())
                    .add(region);
            }
            for (OcrResult.Page page : outcome.result().pages()) {
                if (page.templatePageNo() != null) {
                    templatePageByPage.put(new PageKey(documentId, page.pageNo()), page.templatePageNo());
                }
            }
        }

        Map<Integer, List<OcrRequest.QuestionBox>> templateBoxes = templateBoxes(pages, questionIdByCode.keySet());
        AssignmentPlan plan = new AssignmentPlan(questions);
        Map<Integer, List<Integer>> studentPagesByTemplatePage = new LinkedHashMap<>();
        for (SubmissionPageRow page : pages) {
            Integer templatePageNo = templatePageByPage.get(new PageKey(page.documentId(), page.documentPageNo()));
            if (templatePageNo != null) {
                studentPagesByTemplatePage.computeIfAbsent(templatePageNo, key -> new ArrayList<>())
                    .add(page.pageNo());
            }
        }
        markVersionWarnings(plan, pages, outcomes, templatePageByTemplateBox(templateBoxes),
            studentPagesByTemplatePage);

        Map<Long, byte[]> pageCache = new LinkedHashMap<>();
        for (SubmissionPageRow page : pages) {
            PageKey key = new PageKey(page.documentId(), page.documentPageNo());
            List<WrittenRegion> regions = regionsByPage.getOrDefault(key, List.of());
            Integer templatePageNo = templatePageByPage.get(key);
            if (templatePageNo == null) {
                // 没对上模板：区域只能靠引擎读出来的题号归属，读不出来的留在界面上等教师归类。
                // 页面级的原因已经由 markVersionWarnings 记下，这里不再重复标一遍。
                for (WrittenRegion region : regions) {
                    Long questionId = questionIdByCode.get(region.region().questionCode());
                    if (questionId != null) {
                        plan.assign(questionId, region);
                    } else {
                        plan.unassigned(region, page.pageNo());
                    }
                }
                continue;
            }
            List<OcrRequest.QuestionBox> boxes =
                templateBoxes.getOrDefault(templatePageNo, List.of());
            for (WrittenRegion region : regions) {
                assignRegion(plan, region, boxes, questionIdByCode, page.pageNo());
            }
        }
        markUnassignedWarning(plan);
        markCandidateWarnings(plan);
        insertCandidates(teacherId, version, plan, pageCache);
        persistVersionWarnings(version.versionId(), plan.versionWarnings());
        versions.markAwaitingReview(version, questions.size());
        log.info("答卷候选已物化：versionId={} 题目数={} 未归属区域数={}",
            version.versionId(), questions.size(), plan.unassignedCount());
    }

    /**
     * 把一块区域分给某道题。
     *
     * <p>归属依据有两条，优先级是明确的：
     * <ol>
     *   <li>引擎读出来的题号（{@code questionCode}）：那是它在那块像素上看到的字，是证据；</li>
     *   <li>几何：这块区域压在哪个题框上。学生答卷上的作答本身没有题号，这是主要途径。</li>
     * </ol>
     * 两条都有且指向不同的题时以题号为准，但要标 {@link AnswerWarning#QUESTION_AMBIGUOUS}：
     * 这种矛盾通常意味着拍歪了或者题框没对齐，教师扫一眼就能定，而系统猜错的话
     * 会把一道题的解答算到另一道题头上。
     *
     * <p>落不到任何题上时**不丢弃**：留在 {@code plan} 的未归属集合里，
     * 由 {@link AnswerReviewView.AnswerRegionView#candidateId()} 为空暴露给界面。
     */
    private static void assignRegion(AssignmentPlan plan, WrittenRegion region,
                                     List<OcrRequest.QuestionBox> boxes,
                                     Map<String, Long> questionIdByCode, int studentPageNo) {
        Long byCode = questionIdByCode.get(region.region().questionCode());
        List<BoxOverlap> overlaps = boxes.stream()
            .map(box -> new BoxOverlap(box, coveredFraction(region, box)))
            .filter(overlap -> overlap.fraction() > 0)
            .sorted(Comparator.comparingDouble(BoxOverlap::fraction).reversed())
            .toList();
        if (overlaps.isEmpty()) {
            if (byCode != null) {
                // 题号有、几何上没有：引擎在一处不属于任何题框的地方读到了题号。
                // 以题号为准，因为那多半是题框画小了。
                plan.assign(byCode, region);
                return;
            }
            plan.unassigned(region, studentPageNo);
            return;
        }
        Long geometric = questionIdByCode.get(overlaps.getFirst().box().questionCode());
        if (byCode != null && geometric != null && byCode.longValue() != geometric.longValue()) {
            plan.assign(byCode, region);
            plan.warn(byCode, AnswerWarning.QUESTION_AMBIGUOUS);
            return;
        }
        Long target = byCode != null ? byCode : geometric;
        if (target == null) {
            // 题框对应的题号不在作业里（教师把这道题从作业里去掉过）。不猜，留给教师。
            plan.unassigned(region, studentPageNo);
            return;
        }
        plan.assign(target, region);
        long pressing = overlaps.stream().filter(overlap -> overlap.fraction() >= AMBIGUITY_MIN_OVERLAP).count();
        if (pressing > 1) {
            plan.warn(target, AnswerWarning.QUESTION_AMBIGUOUS);
        }
    }

    /**
     * 一块区域有多大比例压在某个题框里。
     *
     * <p>分母是区域自己的面积，不是题框的面积：要回答的是"这一块落在题框里吗"，
     * 用题框做分母时，一块很小的作答落在很大的题框里会得到接近 0 的比例，
     * 于是它会被判成"不在题框里"。
     */
    private static double coveredFraction(WrittenRegion region, OcrRequest.QuestionBox box) {
        double area = region.region().width() * region.region().height();
        if (area <= 0) {
            return 0;
        }
        double left = Math.max(region.region().x(), box.x());
        double top = Math.max(region.region().y(), box.y());
        double right = Math.min(region.region().x() + region.region().width(), box.x() + box.width());
        double bottom = Math.min(region.region().y() + region.region().height(), box.y() + box.height());
        if (right <= left || bottom <= top) {
            return 0;
        }
        return (right - left) * (bottom - top) / area;
    }

    /**
     * 每次识别带的模板框，按模板页码归拢。
     *
     * <p>从 {@link OcrTemplateProvider} 取而不是另查一遍库：送出去配准的题框与这里用来归属的
     * 题框必须是同一份。两边各查一遍，在教师刚改过整卷导入的那一刻就会分叉——配准说
     * "第 2 页对上了"，归属却按旧题框分配，症状是"认出来了但归不到任何题上"。
     *
     * <p>题号不在作业里的框直接丢掉：那种框对应的题已经不属于这份作业，
     * 拿它当归属依据会把作答挂到一道学生根本不该做的题上。
     */
    private Map<Integer, List<OcrRequest.QuestionBox>> templateBoxes(List<SubmissionPageRow> pages,
                                                                    Set<String> knownCodes) {
        Map<Integer, Map<String, OcrRequest.QuestionBox>> byPage = new LinkedHashMap<>();
        for (long documentId : distinctDocuments(pages)) {
            for (OcrRequest.TemplatePage page : templateProvider.templateFor(documentId)
                .map(OcrRequest.Template::pages).orElse(List.of())) {
                for (OcrRequest.QuestionBox box : page.questions()) {
                    if (knownCodes.contains(box.questionCode())) {
                        byPage.computeIfAbsent(page.pageNo(), key -> new LinkedHashMap<>())
                            .putIfAbsent(box.questionCode(), box);
                    }
                }
            }
        }
        Map<Integer, List<OcrRequest.QuestionBox>> boxes = new LinkedHashMap<>();
        byPage.forEach((pageNo, byCode) -> boxes.put(pageNo, List.copyOf(byCode.values())));
        return boxes;
    }

    /** 模板页码 → 那一页上的题号，用来判断"这一页是不是一道题都没有"。 */
    private static Map<Integer, Set<String>> templatePageByTemplateBox(
        Map<Integer, List<OcrRequest.QuestionBox>> templateBoxes) {
        Map<Integer, Set<String>> codes = new LinkedHashMap<>();
        templateBoxes.forEach((pageNo, boxes) -> {
            Set<String> pageCodes = new LinkedHashSet<>();
            boxes.forEach(box -> pageCodes.add(box.questionCode()));
            codes.put(pageNo, pageCodes);
        });
        return codes;
    }

    /**
     * 整份答卷级别的问题，物化时算一次、存下来。
     *
     * <p>五个码覆盖了"页与模板的关系"这一层可能出的所有事：模板上的页学生没交、两页对上了同一页、
     * 某一页对不上模板、整份没有模板、有作答落在所有题框之外（以及识别本身没跑完/失败了）。
     * 一个都不许省：这些正是教师判断"这份答卷能不能看"的依据，而界面上除了这一份清单，
     * 没有别的地方能说出"第 3 页没交"。
     */
    private static void markVersionWarnings(AssignmentPlan plan, List<SubmissionPageRow> pages,
                                            Map<Long, OcrOutcome> outcomes,
                                            Map<Integer, Set<String>> templateCodes,
                                            Map<Integer, List<Integer>> studentPagesByTemplatePage) {
        for (SubmissionPageRow page : pages) {
            OcrOutcome outcome = outcomes.get(page.documentId());
            if (outcome != null && outcome.result() != null) {
                continue;
            }
            // 区分"失败了"与"还没跑完"：前者要教师去点重新识别，后者只要等一会儿。
            // 当成同一件事会让教师在明明只是慢的识别上反复点重试。
            if (outcome != null && outcome.failureCode() != null) {
                plan.versionWarn(AnswerWarning.OCR_FAILED, "第 " + page.pageNo() + " 页（"
                    + page.fileName() + "）识别失败（" + outcome.failureCode() + "），请重新识别");
            } else {
                String status = outcome == null || outcome.status() == null
                    ? "尚未开始" : "当前状态 " + outcome.status();
                plan.versionWarn(AnswerWarning.OCR_INCOMPLETE, "第 " + page.pageNo() + " 页（"
                    + page.fileName() + "）还没有识别结果（" + status + "），这一页的作答暂时看不到");
            }
        }
        if (templateCodes.isEmpty()) {
            plan.versionWarn(AnswerWarning.TEMPLATE_MISSING,
                "这份作业还没有确认入库的题目，识别结果里没有题号可依，请手工归类作答");
        } else {
            for (Map.Entry<Integer, List<Integer>> entry : studentPagesByTemplatePage.entrySet()) {
                if (entry.getValue().size() > 1) {
                    plan.versionWarn(AnswerWarning.PAGE_DUPLICATE, "第 "
                        + entry.getValue().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("、"))
                        + " 页都对上了试卷的第 " + entry.getKey() + " 页，可能是同一张纸拍了两遍");
                }
            }
            Set<Integer> covered = studentPagesByTemplatePage.keySet();
            for (Integer templatePageNo : templateCodes.keySet()) {
                if (!covered.contains(templatePageNo)) {
                    plan.versionWarn(AnswerWarning.PAGE_MISSING,
                        "试卷第 " + templatePageNo + " 页上的题目在答卷里找不到对应的页，这些题会按空白处理");
                }
            }
            markTemplateMismatch(plan, pages, studentPagesByTemplatePage);
        }
    }

    /**
     * 有页面的识别结果出来了，但那一页对不上任何模板页。
     *
     * <p>与"整份没有模板"分开报：前者是这一页的问题（拍歪了、拍到了别的东西），
     * 后者是这份作业还没准备好。两者的处理完全不同，混成一个码会让教师去改错误的地方。
     */
    private static void markTemplateMismatch(AssignmentPlan plan, List<SubmissionPageRow> pages,
                                             Map<Integer, List<Integer>> studentPagesByTemplatePage) {
        Set<Integer> matchedPageNos = new LinkedHashSet<>();
        studentPagesByTemplatePage.values().forEach(matchedPageNos::addAll);
        for (SubmissionPageRow page : pages) {
            if (!matchedPageNos.contains(page.pageNo())) {
                plan.versionWarn(AnswerWarning.TEMPLATE_MISMATCH, "第 " + page.pageNo()
                    + " 页没对上试卷的任何一页，这一页的作答需要手工归类");
            }
        }
    }

    /**
     * 落在所有题框之外、也没识别出题号的区域。
     *
     * <p>这条警告是"绝不静默丢弃"这条铁律在界面上的落点：那些区域本身照样落库、
     * 照样在页面上显示（{@code candidateId} 为空），但如果没有这一条，
     * 教师就得自己在几十块区域里找出哪几块没归到题上——而"识别出来了却没算进任何一道题"
     * 恰恰是最需要他知道的事。
     *
     * <p>按页聚合成一条：逐块报会在页面上排出几十条一模一样的提示。
     */
    private static void markUnassignedWarning(AssignmentPlan plan) {
        if (plan.unassignedCount() == 0) {
            return;
        }
        String pages = plan.unassignedByPage().keySet().stream().map(String::valueOf)
            .collect(java.util.stream.Collectors.joining("、"));
        plan.versionWarn(AnswerWarning.ANSWER_OUT_OF_BOX, "第 " + pages + " 页上有 "
            + plan.unassignedCount() + " 块识别出的作答不在任何题框内，需要手工归类");
    }

    /**
     * 每道题的候选警告。
     *
     * <p>这些是"这一道题需要教师看一眼"的理由，与整份答卷级别的那几个码分开算：
     * 它们的处理动作不同（改一道题 vs 补一页/重拍）。
     */
    private void markCandidateWarnings(AssignmentPlan plan) {
        for (QuestionRow question : plan.questions()) {
            List<WrittenRegion> regions = plan.regions(question.questionId());
            if (regions.isEmpty()) {
                plan.warn(question.questionId(), AnswerWarning.ANSWER_BLANK);
                continue;
            }
            if (regions.stream().map(WrittenRegion::pageNo).distinct().count() > 1) {
                plan.warn(question.questionId(), AnswerWarning.CROSS_PAGE_ANSWER);
            }
            for (WrittenRegion region : regions) {
                if (region.region().confidence() < LOW_CONFIDENCE_THRESHOLD) {
                    plan.warn(question.questionId(), isFormula(region)
                        ? AnswerWarning.LOW_CONFIDENCE_FORMULA : AnswerWarning.LOW_CONFIDENCE);
                }
            }
        }
    }

    /**
     * 这一块是公式还是文字。
     *
     * <p>判据是"引擎给的类型说它是公式"或"它只有 LaTeX 没有文字"：公式认错一个符号，
     * 整道题的解答就变成另一个意思，所以值得单独一个警告码让教师在界面上先看公式。
     */
    private static boolean isFormula(WrittenRegion region) {
        if ("FORMULA".equals(region.regionType())) {
            return true;
        }
        return region.region().text() == null && region.region().latex() != null
            && !region.region().latex().isBlank();
    }

    private Map<Integer, Long> insertPages(long teacherId, long documentId, OcrResult result,
                                           Map<PageKey, Long> pageFileByPage) {
        Map<Integer, Long> existing = existingPageIds(documentId);
        Map<Integer, Long> pageIds = new LinkedHashMap<>();
        for (OcrResult.Page page : result.pages()) {
            Long existingId = existing.get(page.pageNo());
            if (existingId != null) {
                // 重复物化时复用（唯一键 uk_document_page_no 也拦得住）。正常情况下走不到：
                // 页面与候选在同一个事务里，要么都有要么都没有。
                pageIds.put(page.pageNo(), existingId);
                continue;
            }
            Long pageFileId = pageFileByPage.get(new PageKey(documentId, page.pageNo()));
            if (pageFileId == null) {
                // 学生交过这一页又删掉了。区域照样落库（他写过的字是证据），
                // 只是它不在答卷里，教师看不到——所以页面图退回原始上传文件。
                pageFileId = originalFileId(documentId);
            }
            long storedPageFileId = pageFileId;
            pageIds.put(page.pageNo(), GeneratedKeys.insert(jdbc, """
                insert into document_page(document_id, page_no, page_file_id, original_width, original_height,
                                          template_page_no, alignment_confidence)
                values (:documentId, :pageNo, :pageFileId, :width, :height, :templatePageNo, :alignmentConfidence)
                """, statement -> statement
                .param("documentId", documentId)
                .param("pageNo", page.pageNo())
                .param("pageFileId", storedPageFileId)
                .param("width", page.width())
                .param("height", page.height())
                .param("templatePageNo", page.templatePageNo())
                .param("alignmentConfidence", page.alignmentConfidence())));
        }
        return pageIds;
    }

    /**
     * 建候选行。
     *
     * <p>裁答案图放在这里（而不是等教师点一下）是因为复核界面的主角就是那张图：
     * 教师看到的是学生写的字，不是识别出来的文本；等到点击才裁，等于让界面先显示一个空位。
     * 裁剪失败不拦下整个物化——区域与识别文字都还在，教师可以修框后重裁
     * （{@link AnswerCorrectionCommand#crop()}）。
     */
    private void insertCandidates(long teacherId, VersionRef version, AssignmentPlan plan,
                                  Map<Long, byte[]> pageCache) {
        for (QuestionRow question : plan.questions()) {
            List<WrittenRegion> regions = plan.regions(question.questionId());
            List<Long> regionIds = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            List<String> latexes = new ArrayList<>();
            boolean cropFailed = false;
            for (WrittenRegion region : regions) {
                regionIds.add(region.regionId());
                if (region.text() != null && !region.text().isBlank()) {
                    texts.add(region.text().trim());
                }
                if (region.region().latex() != null && !region.region().latex().isBlank()) {
                    latexes.add(region.region().latex().trim());
                }
                cropFailed |= !cropRegion(teacherId, region.regionId(), pageCache, false);
            }
            Set<AnswerWarning> warnings = plan.warnings(question.questionId());
            if (cropFailed) {
                warnings.add(AnswerWarning.ANSWER_CROP_FAILED);
            }
            GeneratedKeys.insert(jdbc, """
                insert into submission_answer_candidate(submission_version_id, question_id, order_no,
                    answer_text, answer_latex, source_region_ids, confidence, blank, warnings,
                    review_status, version)
                values (:versionId, :questionId, :orderNo, :answerText, :answerLatex, :sourceRegionIds,
                        :confidence, :blank, :warnings, 'PENDING', 0)
                """, statement -> statement
                .param("versionId", version.versionId())
                .param("questionId", question.questionId())
                .param("orderNo", question.questionOrder())
                .param("answerText", String.join("\n", texts))
                .param("answerLatex", joinOrNull(latexes))
                .param("sourceRegionIds", writeJson(regionIds))
                .param("confidence", regions.isEmpty() ? null : minConfidence(regions))
                .param("blank", regions.isEmpty())
                .param("warnings", writeJson(warnings.stream().map(Enum::name).toList())));
        }
    }

    /**
     * 重裁这道题的答案图。
     *
     * <p>与物化时的自动裁剪不同，这里是教师明确要求的重裁，所以**已有的答案图会被换掉**：
     * 教师发现框偏了、改完区域几何之后要重新出图，沿用旧图会让界面继续显示裁歪的那一张——
     * 而教师正是照着那张图核对识别结果的。
     */
    private void cropCandidate(long teacherId, CandidateRow candidate) {
        Map<Long, byte[]> pageCache = new LinkedHashMap<>();
        for (Long regionId : read(candidate.sourceRegionIds(), LONG_LIST)) {
            cropRegion(teacherId, regionId, pageCache, true);
        }
    }

    /**
     * 按区域几何裁出答案图并写回 {@code document_region.crop_file_id}。
     *
     * @param force 已经裁过时是否重裁。物化时复用（同一块区域被两条链路裁两次没有意义），
     *              教师要求重裁时必须覆盖。
     * @return 是否已经有可用的答案图。区域太窄裁不出东西时返回 {@code false}，
     *         由调用方在候选上标 {@link AnswerWarning#ANSWER_CROP_FAILED}：
     *         裁剪失败不是错误，但不能没有痕迹。
     */
    private boolean cropRegion(long teacherId, long regionId, Map<Long, byte[]> pageCache, boolean force) {
        RegionCropRow region = requireCropRow(regionId);
        if (region.cropFileId() != null && !force) {
            return true;
        }
        byte[] pageBytes = pageCache.computeIfAbsent(region.pageFileId(), this::readStoredBytes);
        try {
            byte[] cropped = ImageTransforms.crop(pageBytes, region.x(), region.y(),
                region.width(), region.height());
            StoredFileService.StoredFileView stored = files.storeTeacherFile(teacherId,
                "answer-region-" + regionId + "-crop.png", "image/png", cropped);
            jdbc.sql("""
                    update document_region set crop_file_id = :fileId, updated_at = current_timestamp(3)
                    where id = :id
                    """)
                .param("fileId", stored.id())
                .param("id", regionId)
                .update();
            return true;
        } catch (DomainException exception) {
            log.warn("答案图裁剪失败，区域保留：regionId={} 错误码={}", regionId, exception.code());
            return false;
        }
    }

    // ---------- 读取 ----------

    /**
     * 这一版学生交的页，按学生排定的顺序。
     *
     * <p>{@code pageFileId} 与 {@code rotatedFileId} 都要：识别跑在**原始页面图**上，
     * 所以区域坐标与裁图都必须以 {@code pageFileId} 为准；而教师看图时看的是学生转正之后的
     * 那一张（{@code rotatedFileId}）。用错了框会整体偏移。
     */
    private List<SubmissionPageRow> submissionPages(long versionId) {
        return jdbc.sql("""
                select sp.id, sp.page_no, sp.document_id, sp.document_page_no, sp.page_file_id,
                       sp.rotated_file_id, sp.width, sp.height,
                       (select sf.original_name from document_upload du
                          join stored_file sf on sf.id = du.original_file_id
                         where du.id = sp.document_id) as file_name
                from submission_page sp
                where sp.submission_version_id = :versionId
                order by sp.page_no
                """)
            .param("versionId", versionId)
            .query((rs, rowNum) -> new SubmissionPageRow(rs.getLong("id"), rs.getInt("page_no"),
                rs.getLong("document_id"), rs.getInt("document_page_no"), rs.getLong("page_file_id"),
                rs.getLong("rotated_file_id"), (Integer) rs.getObject("width"),
                (Integer) rs.getObject("height"), rs.getString("file_name")))
            .list();
    }

    private static List<Long> distinctDocuments(List<SubmissionPageRow> pages) {
        return pages.stream().map(SubmissionPageRow::documentId).distinct().toList();
    }

    private Map<PageKey, List<RegionRow>> regions(List<SubmissionPageRow> pages) {
        if (pages.isEmpty()) {
            return Map.of();
        }
        Map<PageKey, List<RegionRow>> byPage = new LinkedHashMap<>();
        jdbc.sql("""
                select dp.document_id, dp.page_no, dr.id, dr.region_type, dr.x, dr.y, dr.width, dr.height,
                       dr.ocr_text, dr.ocr_latex, dr.confidence, dr.crop_file_id
                from document_region dr
                join document_page dp on dp.id = dr.page_id
                where dp.document_id in (:documentIds)
                order by dp.page_no, dr.y, dr.x
                """)
            .param("documentIds", distinctDocuments(pages))
            .query((rs, rowNum) -> new RegionRow(rs.getLong("id"),
                new PageKey(rs.getLong("document_id"), rs.getInt("page_no")), rs.getString("region_type"),
                rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("width"), rs.getDouble("height"),
                rs.getString("ocr_text"), rs.getString("ocr_latex"), nullableDouble(rs, "confidence"),
                (Long) rs.getObject("crop_file_id")))
            .list()
            .forEach(region -> byPage.computeIfAbsent(region.pageKey(), key -> new ArrayList<>()).add(region));
        return byPage;
    }

    private Map<PageKey, PageAlignment> pageAlignments(List<SubmissionPageRow> pages) {
        if (pages.isEmpty()) {
            return Map.of();
        }
        Map<PageKey, PageAlignment> alignments = new LinkedHashMap<>();
        jdbc.sql("""
                select document_id, page_no, template_page_no, alignment_confidence
                from document_page where document_id in (:documentIds)
                """)
            .param("documentIds", distinctDocuments(pages))
            .query((rs, rowNum) -> new PageAlignment(new PageKey(rs.getLong("document_id"),
                rs.getInt("page_no")), (Integer) rs.getObject("template_page_no"),
                nullableDouble(rs, "alignment_confidence")))
            .list()
            .forEach(alignment -> alignments.put(alignment.pageKey(), alignment));
        return alignments;
    }

    private List<CandidateRow> candidates(long versionId) {
        return jdbc.sql("""
                select c.id, c.question_id, q.question_code, aq.question_order, c.order_no, c.answer_text,
                       c.answer_latex, c.blank, c.confidence, c.review_status, c.version, c.warnings,
                       c.source_region_ids
                from submission_answer_candidate c
                join question q on q.id = c.question_id
                left join assignment_question aq on aq.assignment_id = :assignmentId
                     and aq.question_id = c.question_id
                where c.submission_version_id = :versionId
                order by c.order_no, c.id
                """)
            .param("assignmentId", assignmentIdOf(versionId))
            .param("versionId", versionId)
            .query((rs, rowNum) -> new CandidateRow(rs.getLong("id"), rs.getLong("question_id"),
                rs.getString("question_code"), rs.getInt("question_order"), (Integer) rs.getObject("order_no"),
                rs.getString("answer_text"), rs.getString("answer_latex"), rs.getBoolean("blank"),
                nullableDouble(rs, "confidence"), rs.getString("review_status"), rs.getInt("version"),
                rs.getString("warnings"), rs.getString("source_region_ids")))
            .list();
    }

    private CandidateRow requireCandidate(long versionId, long candidateId) {
        return candidates(versionId).stream()
            .filter(candidate -> candidate.candidateId() == candidateId)
            .findFirst()
            .orElseThrow(() -> new DomainException("ANSWER_CANDIDATE_NOT_FOUND",
                "没有这条答案候选", HttpStatus.NOT_FOUND));
    }

    private List<QuestionRow> assignmentQuestions(long assignmentId) {
        return jdbc.sql("""
                select q.id, q.question_code, aq.question_order
                from assignment_question aq
                join question q on q.id = aq.question_id
                where aq.assignment_id = :assignmentId
                order by aq.question_order
                """)
            .param("assignmentId", assignmentId)
            .query((rs, rowNum) -> new QuestionRow(rs.getLong("id"), rs.getString("question_code"),
                rs.getInt("question_order")))
            .list();
    }

    private int questionOrder(long assignmentId, long questionId) {
        Integer order = jdbc.sql("""
                select question_order from assignment_question
                where assignment_id = :assignmentId and question_id = :questionId
                """)
            .param("assignmentId", assignmentId)
            .param("questionId", questionId)
            .query(Integer.class)
            .optional()
            .orElseThrow(() -> new DomainException("ANSWER_QUESTION_NOT_IN_ASSIGNMENT",
                "这道题不属于这份作业", HttpStatus.BAD_REQUEST));
        return order == null ? 0 : order;
    }

    /**
     * 这份文档的识别结果。
     *
     * <p>只认处理版本 1（与入队时一致）。{@code raw_output} 为空表示还没跑完或跑失败了，
     * 这时把任务状态与失败码带回去，由调用方在整份答卷的警告里说出来——
     * 静默当成"没有区域"，教师会以为学生整页空白。
     */
    private OcrOutcome ocrOutcome(long documentId) {
        return jdbc.sql("""
                select status, failure_code, raw_output from ocr_task
                where document_id = :documentId and processing_version = :processingVersion
                order by id desc limit 1
                """)
            .param("documentId", documentId)
            .param("processingVersion", PROCESSING_VERSION)
            .query((rs, rowNum) -> new OcrOutcome(rs.getString("status"), rs.getString("failure_code"),
                readOcrResult(rs.getString("raw_output"))))
            .optional()
            .orElseGet(() -> new OcrOutcome(null, null, null));
    }

    private OcrResult readOcrResult(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            OcrResult result = objectMapper.readValue(raw, OcrResult.class);
            result.requireValid();
            return result;
        } catch (JacksonException exception) {
            // 库里存着读不出来的识别结果属于数据损坏：报 502，而不是当成"没有区域"继续跑。
            throw new DomainException("OCR_CONTRACT_INVALID", "识别结果无法解析",
                HttpStatus.BAD_GATEWAY, exception);
        }
    }

    private Map<Integer, Long> existingPageIds(long documentId) {
        Map<Integer, Long> pageIds = new LinkedHashMap<>();
        jdbc.sql("select page_no, id from document_page where document_id = :documentId")
            .param("documentId", documentId)
            .query((rs, rowNum) -> Map.entry(rs.getInt("page_no"), rs.getLong("id")))
            .list()
            .forEach(entry -> pageIds.put(entry.getKey(), entry.getValue()));
        return pageIds;
    }

    private long originalFileId(long documentId) {
        Long fileId = jdbc.sql("select original_file_id from document_upload where id = :id")
            .param("id", documentId)
            .query(Long.class)
            .optional()
            .orElseThrow(() -> new DomainException("DOCUMENT_NOT_FOUND", "文档不存在", HttpStatus.NOT_FOUND));
        return fileId == null ? 0L : fileId;
    }

    private RegionCropRow requireCropRow(long regionId) {
        return jdbc.sql("""
                select dr.id, dr.x, dr.y, dr.width, dr.height, dr.crop_file_id, dp.page_file_id
                from document_region dr
                join document_page dp on dp.id = dr.page_id
                where dr.id = :id
                """)
            .param("id", regionId)
            .query((rs, rowNum) -> new RegionCropRow(rs.getLong("id"), rs.getDouble("x"), rs.getDouble("y"),
                rs.getDouble("width"), rs.getDouble("height"), (Long) rs.getObject("crop_file_id"),
                rs.getLong("page_file_id")))
            .optional()
            .orElseThrow(() -> new DomainException("ANSWER_REGION_NOT_FOUND",
                "区域不存在", HttpStatus.NOT_FOUND));
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

    private int candidateCount(long versionId) {
        Integer count = jdbc.sql("""
                select count(*) from submission_answer_candidate where submission_version_id = :versionId
                """)
            .param("versionId", versionId)
            .query(Integer.class)
            .single();
        return count == null ? 0 : count;
    }

    private long assignmentIdOf(long versionId) {
        Long assignmentId = jdbc.sql("select assignment_id from submission_version where id = :id")
            .param("id", versionId)
            .query(Long.class)
            .optional()
            .orElseThrow(() -> new DomainException("SUBMISSION_VERSION_NOT_FOUND",
                "提交记录不存在", HttpStatus.NOT_FOUND));
        return assignmentId == null ? 0L : assignmentId;
    }

    private String studentName(long studentId) {
        return jdbc.sql("select name from student where id = :id")
            .param("id", studentId)
            .query(String.class)
            .optional()
            .orElse(null);
    }

    private String extractionWarnings(long versionId) {
        return jdbc.sql("select extraction_warnings from submission_version where id = :id")
            .param("id", versionId)
            .query(String.class)
            .optional()
            .orElse(null);
    }

    private void persistVersionWarnings(long versionId, List<AnswerReviewView.WarningView> warnings) {
        jdbc.sql("""
                update submission_version set extraction_warnings = :warnings,
                    updated_at = current_timestamp(3)
                where id = :id
                """)
            .param("warnings", writeJson(warnings))
            .param("id", versionId)
            .update();
    }

    // ---------- 序列化 ----------

    /**
     * 读一个可空的 {@code decimal} 列。
     *
     * <p>不能写成 {@code (Double) rs.getObject(...)}：{@code decimal(5,4)} 在 H2 与 MySQL 上
     * 都按 {@code BigDecimal} 取出来，强转 Double 会在运行时炸掉整条链路。
     * 走 {@code Number} 则两种驱动都收，列宽改成 {@code double} 时也不用动。
     */
    private static Double nullableDouble(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Number value = (Number) rs.getObject(column);
        return value == null ? null : value.doubleValue();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("答卷候选数据无法序列化", exception);
        }
    }

    private <T> List<T> read(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException exception) {
            throw new IllegalStateException("答卷候选数据损坏", exception);
        }
    }

    private static Double minConfidence(List<WrittenRegion> regions) {
        return regions.stream().mapToDouble(region -> region.region().confidence()).min().orElse(0.0);
    }

    private static String joinOrNull(List<String> values) {
        return values.isEmpty() ? null : String.join("\n", values);
    }

    // ---------- 归属草稿 ----------

    /**
     * 归属结果，物化阶段临时存在。
     *
     * <p>它不是视图也不是表结构：只是"每道题拿到哪些区域、值得提醒什么"的两张表，
     * 让归属的判定逻辑（几何、题号、冲突）与写库的代码分开，各自读起来是一个意思。
     */
    private static final class AssignmentPlan {

        private final List<QuestionRow> questions;
        private final Map<Long, List<WrittenRegion>> regions = new LinkedHashMap<>();
        private final Map<Long, Set<AnswerWarning>> warnings = new LinkedHashMap<>();
        private final List<AnswerReviewView.WarningView> versionWarnings = new ArrayList<>();
        private final Map<Integer, Integer> unassignedByPage = new LinkedHashMap<>();

        AssignmentPlan(List<QuestionRow> questions) {
            this.questions = questions;
        }

        List<QuestionRow> questions() {
            return questions;
        }

        void assign(long questionId, WrittenRegion region) {
            regions.computeIfAbsent(questionId, key -> new ArrayList<>()).add(region);
        }

        void warn(long questionId, AnswerWarning warning) {
            warnings.computeIfAbsent(questionId, key -> new LinkedHashSet<>()).add(warning);
        }

        Set<AnswerWarning> warnings(long questionId) {
            return warnings.computeIfAbsent(questionId, key -> new LinkedHashSet<>());
        }

        List<WrittenRegion> regions(long questionId) {
            return regions.getOrDefault(questionId, List.of());
        }

        /**
         * 一块没归到任何题上的区域。
         *
         * <p>按**学生排的页码**记账，不是按区域条数：界面上要说的是"第 2 页上有 3 块没归类"，
         * 一句能照着一页去处理的话。逐块报会把一条提示变成几十条，
         * 而教师面对一屏重复的条目只会全部划过去。
         */
        void unassigned(WrittenRegion region, int studentPageNo) {
            unassignedByPage.merge(studentPageNo, 1, Integer::sum);
        }

        Map<Integer, Integer> unassignedByPage() {
            return unassignedByPage;
        }

        int unassignedCount() {
            return unassignedByPage.values().stream().mapToInt(Integer::intValue).sum();
        }

        void versionWarn(AnswerWarning warning, String detail) {
            boolean duplicated = versionWarnings.stream()
                .anyMatch(existing -> existing.code().equals(warning.name()) && existing.detail().equals(detail));
            if (!duplicated) {
                versionWarnings.add(new AnswerReviewView.WarningView(warning.name(), detail));
            }
        }

        List<AnswerReviewView.WarningView> versionWarnings() {
            return List.copyOf(versionWarnings);
        }
    }

    // ---------- 行 ----------

    private record SubmissionPageRow(long submissionPageId, int pageNo, long documentId, int documentPageNo,
                                     long pageFileId, long rotatedFileId, Integer width, Integer height,
                                     String fileName) {
    }

    /** 页的身份：文档 + 文档内页码。学生排的页码会变，这一对不会。 */
    private record PageKey(long documentId, int pageNo) {
    }

    private record PageAlignment(PageKey pageKey, Integer templatePageNo, Double confidence) {

        static final PageAlignment NONE = new PageAlignment(null, null, null);
    }

    private record QuestionRow(long questionId, String questionCode, int questionOrder) {
    }

    private record CandidateRow(long candidateId, long questionId, String questionCode, int questionOrder,
                                Integer orderNo, String answerText, String answerLatex, boolean blank,
                                Double confidence, String reviewStatus, int version, String warnings,
                                String sourceRegionIds) {
    }

    private record RegionRow(long regionId, PageKey pageKey, String regionType, double x, double y,
                             double width, double height, String ocrText, String ocrLatex,
                             Double confidence, Long cropFileId) {
    }

    private record RegionCropRow(long regionId, double x, double y, double width, double height,
                                 Long cropFileId, long pageFileId) {
    }

    /** 一次识别的结果与它的任务状态。{@code result} 为空表示还没有可用结果。 */
    private record OcrOutcome(String status, String failureCode, OcrResult result) {
    }

    private record BoxOverlap(OcrRequest.QuestionBox box, double fraction) {
    }
}
