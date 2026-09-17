package com.homework.analysis.paper;

import com.homework.analysis.document.ImageTransforms;
import com.homework.analysis.document.OcrRegionWriter;
import com.homework.analysis.document.OcrRegionWriter.WrittenRegion;
import com.homework.analysis.document.PdfPageRenderer;
import com.homework.analysis.document.StoredFileService;
import com.homework.analysis.ocr.OcrResult;
import com.homework.analysis.shared.error.DomainException;
import com.homework.analysis.shared.jdbc.GeneratedKeys;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 OCR 原始结果落成"可校对的数据"：页面、区域、候选题。
 *
 * <p>三件事必须一起做，不能拆开：
 * <ol>
 *   <li><b>页面与区域先落库</b>。区域是教师校对时唯一能核对像素的依据，候选只是区域的一种分组视图；
 *       先建候选再补区域，就会出现"候选指向不存在的框"的中间态。</li>
 *   <li><b>区域一个都不删</b>。合并与拆分换的是分组，不是区域集合——把两块框合并时如果删掉其中一块，
 *       教师就再也无法判断合并是否正确。</li>
 *   <li><b>这里不写 {@code question}</b>。候选不是题目，确认之前题库必须保持干净。</li>
 * </ol>
 *
 * <p>分组依据是**文本里的题号**而不是区域类型：PaddleOCR 的默认模式给的是通用文本块，
 * 它并不知道哪里是一道新题的开头。真实的题号信息在文字里（"12."、"（3）"），
 * 因此"以题号开头的文本块"就是分组锚点。
 */
@Component
class PaperCandidateMaterializer {

    /**
     * 题号锚点：行首可选括号 + 1..199 的数字 + 一个分隔符。
     *
     * <p>上限 199 不是随手取的——没有上限时，"2024." 这样的年份也会被当成题号，
     * 于是一行页眉就会凭空多出一道题。
     */
    private static final Pattern QUESTION_NUMBER = Pattern.compile("^\\s*[（(]?\\s*(\\d{1,3})\\s*[.、．)）]");

    /** 分值提示，例如"（8分）"。分值写在题干里，OCR 不可能从版式里直接读出来。 */
    private static final Pattern SCORE_HINT = Pattern.compile("[（(]\\s*(\\d{1,3})\\s*分\\s*[)）]");

    private static final Pattern CHOICE_OPTION = Pattern.compile("(?m)^\\s*[A-DＡ-Ｄ]\\s*[.、．)）]");

    private final JdbcClient jdbc;
    private final StoredFileService files;
    private final PdfPageRenderer pdfRenderer;
    private final ObjectMapper objectMapper;
    private final OcrRegionWriter regionWriter;

    PaperCandidateMaterializer(JdbcClient jdbc, StoredFileService files, PdfPageRenderer pdfRenderer,
                               ObjectMapper objectMapper, OcrRegionWriter regionWriter) {
        this.jdbc = jdbc;
        this.files = files;
        this.pdfRenderer = pdfRenderer;
        this.objectMapper = objectMapper;
        this.regionWriter = regionWriter;
    }

    /**
     * 落库并返回新建候选的主键（按阅读顺序）。
     *
     * @param source 原卷文档：文档 id、类型、原始文件 id 与字节
     */
    List<Long> materialize(long teacherId, SourceDocument source, OcrResult result) {
        result.requireValid();
        Map<Integer, Long> pageIds = insertPages(teacherId, source, result);
        List<WrittenRegion> regions = regionWriter.write(pageIds, result);
        return insertCandidates(source, group(regions, source.documentKind()));
    }

    /**
     * 页面记录与页面图。
     *
     * <p>PDF 才会渲染出新页面图；图片上传直接复用原始文件作为页面图——重新编码一份内容相同的图，
     * 只会让同一次上传占用两份存储，且两份的 sha256 还不相等（PNG 重编码不保证字节一致），
     * 让"这份文件是不是那一份"变成一个需要逐字节比对才能回答的问题。
     */
    private Map<Integer, Long> insertPages(long teacherId, SourceDocument source, OcrResult result) {
        List<PdfPageRenderer.PageImage> rendered = source.isPdf() ? renderPdf(source) : List.of();
        Map<Integer, Long> pageIds = new LinkedHashMap<>();
        for (OcrResult.Page page : result.pages()) {
            long pageFileId;
            Long thumbnailFileId;
            if (source.isPdf()) {
                PdfPageRenderer.PageImage image = rendered.stream()
                    .filter(candidate -> candidate.pageNo() == page.pageNo())
                    .findFirst()
                    .orElseThrow(() -> new DomainException("OCR_PAGE_MISSING",
                        "识别结果引用了不存在的页码：" + page.pageNo(), HttpStatus.BAD_GATEWAY));
                byte[] pageBytes = image.png();
                pageFileId = files.storeTeacherFile(teacherId, "page-" + page.pageNo() + ".png",
                    "image/png", pageBytes).id();
                thumbnailFileId = storeThumbnail(teacherId, page.pageNo(), pageBytes);
            } else {
                pageFileId = source.originalFileId();
                thumbnailFileId = storeThumbnail(teacherId, page.pageNo(), source.content());
            }
            // lambda 只能捕获有效 final 的局部变量；上面两个变量是分支赋值的，
            // 所以在这里复制一份再进参数绑定。
            long documentId = source.documentId();
            long storedPageFileId = pageFileId;
            Long storedThumbnailFileId = thumbnailFileId;
            long pageId = GeneratedKeys.insert(jdbc, """
                insert into document_page(document_id, page_no, page_file_id, thumbnail_file_id,
                                          original_width, original_height)
                values (:documentId, :pageNo, :pageFileId, :thumbnailFileId, :width, :height)
                """, statement -> statement
                .param("documentId", documentId)
                .param("pageNo", page.pageNo())
                .param("pageFileId", storedPageFileId)
                .param("thumbnailFileId", storedThumbnailFileId)
                .param("width", page.width())
                .param("height", page.height()));
            pageIds.put(page.pageNo(), pageId);
        }
        return pageIds;
    }

    /** 缩略图与原图字节相同时不重复存一份：那种"缩略图"只是同一张图的两个 id。 */
    private Long storeThumbnail(long teacherId, int pageNo, byte[] pageBytes) {
        byte[] thumbnail = ImageTransforms.thumbnail(pageBytes);
        if (thumbnail == pageBytes) {
            return null;
        }
        return files.storeTeacherFile(teacherId, "page-" + pageNo + "-thumb.png", "image/png", thumbnail).id();
    }

    private List<PdfPageRenderer.PageImage> renderPdf(SourceDocument source) {
        try {
            return pdfRenderer.render(source.content());
        } catch (IOException exception) {
            throw new DomainException("OCR_INPUT_INVALID", "上传的 PDF 无法解析",
                HttpStatus.UNPROCESSABLE_ENTITY, exception);
        }
    }

    /**
     * 按题号锚点分组。
     *
     * <p>先把全部区域摊平成一个有序序列再扫描，而不是逐页处理：一道题的题干与题图完全可能跨页，
     * 逐页分组会把它们劈成两道题，而那种错误在界面上看起来只是"多了一道空题"，很难被发现。
     * 代价是跨页分组本身也变得不可确定，所以跨页的组会带上
     * {@code AMBIGUOUS_CROSS_PAGE_GROUP} 警告，让教师去确认边界。
     */
    private List<CandidateDraft> group(List<WrittenRegion> rows, String documentKind) {
        List<CandidateDraft> drafts = new ArrayList<>();
        CandidateDraft current = null;
        for (WrittenRegion row : rows) {
            if (QUESTION_NUMBER.matcher(textOf(row)).find()
                && OcrRegionWriter.DEFAULT_REGION_TYPE.equals(row.regionType())
                && isPlausibleNumber(row)) {
                current = new CandidateDraft(row);
                drafts.add(current);
            } else if (current == null) {
                current = new CandidateDraft(row);
                drafts.add(current);
            } else {
                current.add(row);
            }
        }
        markWarnings(drafts, documentKind);
        return drafts;
    }

    private static boolean isPlausibleNumber(WrittenRegion row) {
        Matcher matcher = QUESTION_NUMBER.matcher(textOf(row));
        if (!matcher.find()) {
            return false;
        }
        return Integer.parseInt(matcher.group(1)) <= 199;
    }

    private static void markWarnings(List<CandidateDraft> drafts, String documentKind) {
        for (CandidateDraft draft : drafts) {
            if (draft.confidence() < PaperImportService.LOW_CONFIDENCE_THRESHOLD) {
                draft.warn(PaperImportService.WARN_LOW_CONFIDENCE);
            }
            if (draft.questionCode() == null) {
                draft.warn(PaperImportService.WARN_MISSING_QUESTION_CODE);
            }
            if (draft.pageNumbers().size() > 1) {
                draft.warn(PaperImportService.WARN_AMBIGUOUS_CROSS_PAGE_GROUP);
            }
            if (PaperImportService.KIND_EXAM_PAPER.equals(documentKind)) {
                if (draft.detectedScore(documentKind) == null) {
                    draft.warn(PaperImportService.WARN_SCORE_NOT_DETECTED);
                }
                if (draft.detectedType(documentKind) == null) {
                    draft.warn(PaperImportService.WARN_QUESTION_TYPE_UNKNOWN);
                }
            }
        }
        markOverlaps(drafts);
    }

    /**
     * 同页两个题号锚点的框相交时各自加警告。
     *
     * <p>这种重叠几乎总是漏检或重复检测的征兆：两个框指向同一片像素，合成题目后题干会重复两遍，
     * 而目视检查一串文本很难发现重复，所以要在数据层面标出来。
     */
    private static void markOverlaps(List<CandidateDraft> drafts) {
        for (int i = 0; i < drafts.size(); i++) {
            for (int j = i + 1; j < drafts.size(); j++) {
                CandidateDraft first = drafts.get(i);
                CandidateDraft second = drafts.get(j);
                if (first.firstPageNo() != second.firstPageNo()) {
                    continue;
                }
                if (first.firstRegion().intersects(second.firstRegion())) {
                    first.warn(PaperImportService.WARN_OVERLAPPING_QUESTION_BOX);
                    second.warn(PaperImportService.WARN_OVERLAPPING_QUESTION_BOX);
                }
            }
        }
    }

    private List<Long> insertCandidates(SourceDocument source, List<CandidateDraft> drafts) {
        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < drafts.size(); index++) {
            CandidateDraft draft = drafts.get(index);
            int orderNo = index + 1;
            ids.add(GeneratedKeys.insert(jdbc, """
                insert into paper_question_candidate(assignment_id, document_id, document_kind, order_no,
                    question_code, question_type, content, standard_answer, accepted_answers, rubric_items,
                    total_score, difficulty, source_region_ids, asset_region_ids, confidence, warnings,
                    review_status, version)
                values (:assignmentId, :documentId, :documentKind, :orderNo,
                    :questionCode, :questionType, :content, :standardAnswer, '[]', '[]',
                    :totalScore, 'MEDIUM', :sourceRegionIds, :assetRegionIds, :confidence, :warnings,
                    'PENDING', 0)
                """, statement -> statement
                .param("assignmentId", source.assignmentId())
                .param("documentId", source.documentId())
                .param("documentKind", source.documentKind())
                .param("orderNo", orderNo)
                .param("questionCode", draft.questionCode())
                .param("questionType", draft.detectedType(source.documentKind()))
                .param("content", draft.content(source.documentKind()))
                .param("standardAnswer", draft.standardAnswer(source.documentKind()))
                .param("totalScore", draft.detectedScore(source.documentKind()))
                .param("sourceRegionIds", writeJson(draft.regionIds()))
                .param("assetRegionIds", writeJson(draft.assetRegionIds()))
                .param("confidence", draft.confidence())
                .param("warnings", writeJson(List.copyOf(draft.warnings())))));
        }
        return ids;
    }

    private static String textOf(WrittenRegion row) {
        return row.text();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("候选数据无法序列化", exception);
        }
    }

    /** 原卷文档：候选与页面都要能追溯到它。 */
    record SourceDocument(long assignmentId, long documentId, String documentKind,
                          long originalFileId, String mimeType, byte[] content) {

        boolean isPdf() {
            return "application/pdf".equals(mimeType);
        }
    }

    /** 分组过程中的候选草稿；只在物化阶段存活，不对外暴露。 */
    private static final class CandidateDraft {

        private final List<WrittenRegion> regions = new ArrayList<>();
        private final Set<Integer> pageNumbers = new LinkedHashSet<>();
        private final Set<String> warnings = new LinkedHashSet<>();

        CandidateDraft(WrittenRegion first) {
            add(first);
        }

        void add(WrittenRegion row) {
            regions.add(row);
            pageNumbers.add(row.pageNo());
        }

        void warn(String code) {
            warnings.add(code);
        }

        WrittenRegion firstRegion() {
            return regions.getFirst();
        }

        int firstPageNo() {
            return regions.getFirst().pageNo();
        }

        Set<Integer> pageNumbers() {
            return pageNumbers;
        }

        List<Long> regionIds() {
            return regions.stream().map(WrittenRegion::regionId).toList();
        }

        List<Long> assetRegionIds() {
            return regions.stream()
                .filter(row -> "FIGURE".equals(row.regionType()))
                .map(WrittenRegion::regionId)
                .toList();
        }

        double confidence() {
            return regions.stream().mapToDouble(row -> row.region().confidence()).min().orElse(0.0);
        }

        Set<String> warnings() {
            return warnings;
        }

        String questionCode() {
            Matcher matcher = QUESTION_NUMBER.matcher(textOf(regions.getFirst()));
            return matcher.find() ? matcher.group(1) : null;
        }

        /**
         * 题干：把组成这道题的所有区域文本按顺序拼起来。
         *
         * <p>答案卷的这一列恒为空——答案卷的候选永远不会变成题目，
         * 把它的文本同时写进题干列，只会让"这一列到底代表什么"变得含混。
         */
        String content(String documentKind) {
            return PaperImportService.KIND_EXAM_PAPER.equals(documentKind) ? joinedText() : null;
        }

        String standardAnswer(String documentKind) {
            return PaperImportService.KIND_ANSWER_KEY.equals(documentKind) ? joinedText() : null;
        }

        private String joinedText() {
            StringBuilder builder = new StringBuilder();
            for (WrittenRegion row : regions) {
                String text = row.text().trim();
                if (text.isEmpty()) {
                    continue;
                }
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append(text);
            }
            return builder.isEmpty() ? null : builder.toString();
        }

        Integer detectedScore(String documentKind) {
            if (!PaperImportService.KIND_EXAM_PAPER.equals(documentKind)) {
                // 答案卷上的分值不属于"这道题多少分"，它只是解析里顺带提到的数字。
                return null;
            }
            Matcher matcher = SCORE_HINT.matcher(joinedTextOrEmpty());
            return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
        }

        /**
         * 题型推断。
         *
         * <p>刻意保留"推断不出来"这个结果（返回 null）而不是兜底成某一个类型：兜错类型会让一道题
         * 在确认后带着错误的评分方式进题库——过程题按客观题走就永远拿不到分项评分，
         * 而界面上看不出任何异常。交给教师选，是这里唯一不会静默出错的选项。
         */
        String detectedType(String documentKind) {
            if (!PaperImportService.KIND_EXAM_PAPER.equals(documentKind)) {
                return null;
            }
            String text = joinedTextOrEmpty();
            long optionMarkers = CHOICE_OPTION.matcher(text).results().count();
            if (optionMarkers >= 2) {
                return "SINGLE_CHOICE";
            }
            if (text.contains("____") || text.contains("（ ）") || text.contains("( )")) {
                return "FILL_BLANK";
            }
            if (text.contains("解：") || text.contains("证明") || text.contains("计算") || text.contains("求")) {
                return "SOLUTION";
            }
            boolean hasFigure = regions.stream().anyMatch(row -> "FIGURE".equals(row.regionType()));
            return hasFigure ? "SOLUTION" : null;
        }

        private String joinedTextOrEmpty() {
            String joined = joinedText();
            return joined == null ? "" : joined;
        }
    }
}
