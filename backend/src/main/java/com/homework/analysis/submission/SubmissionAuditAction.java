package com.homework.analysis.submission;

/**
 * 提交版本上的审计动作。
 *
 * <p>只追加不修改：状态列只记得住最后一步，而"这份答卷被谁动过"是申诉时唯一能拿出来的东西。
 *
 * <p>取值集合与 {@code submission_audit.action} 的注释一致，新增动作必须同时改迁移里的注释，
 * 否则库里的注释会变成一个骗人的清单。
 */
public enum SubmissionAuditAction {
    /** 学生建了新版本。 */
    CREATED,
    PAGE_ADDED,
    PAGE_REMOVED,
    PAGE_REORDERED,
    PAGE_ROTATED,
    /** 学生确认提交。 */
    SUBMITTED,
    /** 识别状态变化，由系统写入。 */
    OCR_STATE_CHANGED,
    /** 教师改了识别出来的文字或题目映射。 */
    OCR_TEXT_CORRECTED,
    /** 教师确认答案入库。 */
    ANSWERS_CONFIRMED,
    /** 旧版本被新版本取代。 */
    SUPERSEDED,
    /** 开始批改。 */
    LOCKED,
    /** 教师退回。 */
    RETURNED;

    /**
     * 操作者角色。
     *
     * <p>与动作放在同一个文件里：它们是同一条审计行的两个列，改一个不改另一个没有意义。
     * 系统动作（识别状态回写、作业关闭）没有操作人，{@code actor_id} 为空。
     */
    public enum ActorRole {
        STUDENT,
        TEACHER,
        SYSTEM
    }
}
