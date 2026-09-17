package com.homework.analysis.ocr;

import java.util.Optional;

/**
 * 给 OCR 请求提供答卷模板几何。
 *
 * <p>为什么是一个接口而不是直接查库：识别任务对"要认的是哪份文档"之外一无所知，
 * 而模板属于另一条业务链路（学生答卷要模板，整卷导入不要）。把两者的关系做成注入点，
 * 识别任务就只管"把请求拼齐"，不必知道模板是怎么算出来的。
 *
 * <p>没有实现时注入 {@code Optional.empty()}：整卷导入的场景就是这样，模板为空
 * 表示"照卷面上的题号分组"，而不是"缺了个东西所以识别不了"。
 */
public interface OcrTemplateProvider {

    /** 这份文档要用的模板；{@code Optional.empty()} 表示不带模板。 */
    Optional<OcrRequest.Template> templateFor(long documentId);
}
