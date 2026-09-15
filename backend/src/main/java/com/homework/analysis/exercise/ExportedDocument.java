package com.homework.analysis.exercise;

import java.util.List;

/**
 * 导出结果：文档字节，加上没能转成 Unicode 的题目题号。
 *
 * <p>两者必须一起返回。只返回字节的话，调用方很容易忘记"这次导出里有公式降级了"，
 * 教师就会拿到一份悄悄变了样的文档。
 *
 * <p>字节数组是可变的，所以在进出两端各复制一份：调用方改不到内部状态，
 * 也拿不到可以事后篡改的引用。
 */
public record ExportedDocument(byte[] content, List<String> fallbackQuestionCodes) {
    public ExportedDocument {
        content = content.clone();
        fallbackQuestionCodes = List.copyOf(fallbackQuestionCodes);
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
