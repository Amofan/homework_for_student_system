package com.homework.analysis.paper;

/**
 * 题目配图的用途。
 *
 * <p>三种角色在渲染时走不同分支，所以必须是枚举而不是自由字符串：数据库列的取值域由这里定义，
 * 写入前一律经过本类型校验，前端不会收到一个自己无法解释的角色。
 */
public enum QuestionAssetRole {
    /** 题图，属于题干的一部分。 */
    STEM_FIGURE,
    /** 来源裁剪图：证明这道题是从原卷哪一块识别出来的，用于教师回溯。 */
    SOURCE_CROP,
    /** 参考答案图，只在教师校对时展示，不发给学生。 */
    REFERENCE_IMAGE;

    /** 只做大小写归一化，不做别名——别名会让"写错一个角色名"变成静默通过。 */
    public static QuestionAssetRole parse(String raw) {
        if (raw == null) {
            return null;
        }
        return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
