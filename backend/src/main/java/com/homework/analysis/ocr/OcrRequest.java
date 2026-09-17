package com.homework.analysis.ocr;

import java.util.List;

/**
 * 送交 OCR 服务的分析请求。
 *
 * <p>{@code schemaVersion} 固定为 {@code v1}，两端都要校验：契约一旦上线，改字段必须换版本号，
 * 否则服务端换了字段含义而调用方无感，识别结果会被静默解析成错的东西。
 *
 * <p>页面图以 base64 内联，而不是让 OCR 服务按存储键去取：OCR 服务在内部网络、
 * 无业务库凭据，也不应该拿到对象存储的读写权限。代价是体积放大约三分之一，
 * 因此有 {@code app.ocr.max-request-bytes} 兜底。
 *
 * <p>{@code documentKind} 决定服务端用哪套版面策略（试卷、解析卷、学生答卷的版面差异很大）。
 *
 * <p>{@code template} 是答卷模板的几何：教师确认过的题号与它们在模板页上的边框。
 * 学生答卷必须带（手写体上没有可靠的题号锚点，分组只能靠"这一块落在第几题的框里"），
 * 整卷导入不带（那时要认的正是卷面上印着的题号本身，给模板反而会让识别结果向模板抄）。
 * 可空，缺省表示不带模板。
 */
public record OcrRequest(
    String schemaVersion,
    String documentKind,
    int processingVersion,
    List<Page> pages,
    Template template) {

    public static final String SCHEMA_VERSION = "v1";

    public OcrRequest(String documentKind, int processingVersion, List<Page> pages) {
        this(SCHEMA_VERSION, documentKind, processingVersion, pages, null);
    }

    public OcrRequest(String documentKind, int processingVersion, List<Page> pages, Template template) {
        this(SCHEMA_VERSION, documentKind, processingVersion, pages, template);
    }

    /** 单页输入。{@code pageNo} 从 1 开始，与 {@code document_page.page_no} 一致。 */
    public record Page(int pageNo, String contentBase64) {}

    /** 模板几何。{@code pages} 为空表示"有模板但一页题目框都没有"，效果与不带模板相同。 */
    public record Template(List<TemplatePage> pages) {}

    /**
     * 模板的一页。
     *
     * <p>{@code pageNo} 是模板卷自己的页码（从 1 开始），不是答卷的页码：学生交的第 3 页
     * 完全可能对应模板的第 1 页，配准要回答的正是这个对应关系。
     */
    public record TemplatePage(int pageNo, List<QuestionBox> questions) {}

    /**
     * 一道题在模板页上占的框，归一化到 {@code 0..1}。
     *
     * <p>多块区域组成一道题时给的是外接框：答卷上的答案只要落在这个范围内就算这道题的，
     * 逐块下发会让"学生写在两块之间的空白处"落到所有框之外。
     */
    public record QuestionBox(String questionCode, double x, double y, double width, double height) {}
}
