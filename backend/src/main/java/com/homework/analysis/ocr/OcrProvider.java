package com.homework.analysis.ocr;

/**
 * OCR 能力入口。
 *
 * <p>业务代码只依赖本接口，不认识 PaddleOCR 也不认识 HTTP：单元测试注入固定响应的假实现，
 * 就不需要下载模型、不需要联网，也不会因为"模型换了个版本"而让整条评分链路的测试变红。
 *
 * <p>实现必须保证：识别结果只用于生成候选数据，绝不写正式题目、答案或成绩。
 */
public interface OcrProvider {

    /**
     * 分析一份文档的各页图像。
     *
     * @throws com.homework.analysis.shared.error.DomainException 上游不可用（code {@code OCR_UNAVAILABLE}）
     *     或返回内容不符合契约（code {@code OCR_CONTRACT_INVALID}）。调用方据此区分"该重试"与"重试也没用"。
     */
    OcrResult analyze(OcrRequest request);
}
