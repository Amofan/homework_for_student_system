package com.homework.analysis.submission;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 提交版本状态。
 *
 * <p>状态只能沿一条线向前走：{@code DRAFT → UPLOADED → PROCESSING → NEEDS_REVIEW → CONFIRMED
 * → LOCKED}，外加三个岔路出口 {@code SUPERSEDED}、{@code RETURNED}、{@code FAILED}。
 * 与 {@link com.homework.analysis.assignment.AssignmentStatus} 一样，这里不提供"任意设置状态"
 * 的入口，只提供判断方法，让"什么时候能改"这件事只有一处定义。
 *
 * <p>学生只能改 {@code DRAFT} 与 {@code UPLOADED} 下的文件与页面；点下确认提交后进入
 * {@code PROCESSING}，从此这一版只读。批改开始（{@code LOCKED}）之后连新版本都建不了，
 * 只有教师退回才重新打开入口。
 *
 * <p>{@code RETURNED} 是终态，不会被后来的 {@code SUPERSEDED} 覆盖：退回原因正是学生要看的
 * 那个东西，把它改写成"已被取代"等于把提示删了。所以一个版本可以是"已退回且已不是当前版本"。
 */
public enum SubmissionVersionStatus {
    /** 刚建出来、还没传任何文件。 */
    DRAFT,
    /** 已经有页面，学生还在整理。 */
    UPLOADED,
    /** 学生已确认提交，等识别。 */
    PROCESSING,
    /** 识别完成，等教师校对答案。 */
    NEEDS_REVIEW,
    /** 教师已确认答案，等批改。 */
    CONFIRMED,
    /** 批改已开始，学生不能再改也不能新建版本。 */
    LOCKED,
    /** 被后续版本取代。 */
    SUPERSEDED,
    /** 教师退回，学生可以新建下一版。 */
    RETURNED,
    /** 识别失败，学生需要重新提交。 */
    FAILED;

    /** 只有这两个状态下学生能增删页面、改顺序、旋转。 */
    private static final Set<SubmissionVersionStatus> EDITABLE = Set.of(DRAFT, UPLOADED);

    /**
     * 已经交给识别或批改链路、还没有被取代或退回的状态。
     *
     * <p>这些状态占着"当前提交"的位置：批改链路按 {@code is_current} 取数，取的必须是其中的一个，
     * 否则会出现"成绩算的是草稿"或者"作业卡在一版没人管的提交上"。
     */
    private static final Set<SubmissionVersionStatus> IN_FLIGHT =
        Set.of(PROCESSING, NEEDS_REVIEW, CONFIRMED, LOCKED);

    /**
     * 教师可以校对答案的状态：识别已经交出去，答案还没有入库。
     *
     * <p>不含 {@code FAILED}：那一版是"这份提交本身没成"，等的是学生重交，
     * 而不是教师在一条注定进不了下一状态的链路上改候选。
     */
    private static final Set<SubmissionVersionStatus> ANSWERS_REVIEWABLE =
        Set.of(PROCESSING, NEEDS_REVIEW);

    /** 答案已经入库（{@code LOCKED} 是它之后唯一的去处）。这两个状态下候选只读。 */
    private static final Set<SubmissionVersionStatus> ANSWERS_CONFIRMED = Set.of(CONFIRMED, LOCKED);

    public static SubmissionVersionStatus parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("提交版本状态为空");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            // 库里出现无法识别的状态属于数据损坏，不是用户错误：让它在 500 里暴露出来，
            // 而不是悄悄退回某个默认状态继续跑。
            throw new IllegalArgumentException("无法识别的提交版本状态：" + raw, exception);
        }
    }

    public boolean editable() {
        return EDITABLE.contains(this);
    }

    /**
     * 可编辑状态的名称，供 SQL 的 {@code in (...)} 列表使用。
     *
     * <p>SQL 里再抄一遍 {@code 'DRAFT', 'UPLOADED'} 就是第二份定义：枚举改了而 SQL 没改时，
     * "哪个状态能改"会在服务层与查询里给出不同答案，而症状是"按钮亮着但列表里找不到草稿"。
     */
    public static Set<String> editableNames() {
        return EDITABLE.stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 直接拼进 SQL {@code in (...)} 的那串 {@code 'DRAFT', 'UPLOADED'}。
     *
     * <p>与 {@link #editableNames()} 并存：那个给 Java 里的判断用，这个给 SQL 用。
     * 拼接是安全的 —— 值来自本枚举的状态名，不含任何外部输入。
     */
    public static String editableNamesSql() {
        return EDITABLE.stream().map(Enum::name).sorted().map(name -> "'" + name + "'")
            .collect(Collectors.joining(", "));
    }

    public boolean inFlight() {
        return IN_FLIGHT.contains(this);
    }

    /**
     * 答案已入库的两个状态名，供 SQL 的 {@code in (...)} 列表使用。
     *
     * <p>与 {@link #editableNamesSql()} 同一个理由：批改入口要按这两个状态过滤，
     * 在 SQL 里再抄一遍 {@code 'CONFIRMED', 'LOCKED'}，枚举改了而那句话没改时，
     * 症状是"答案确认了但批改说没有可批的答卷"。
     */
    public static String answersConfirmedNamesSql() {
        return ANSWERS_CONFIRMED.stream().map(Enum::name).sorted().map(name -> "'" + name + "'")
            .collect(Collectors.joining(", "));
    }

    /** 能不能进答案校对链路（跑识别、改候选、确认入库）。 */
    public boolean answersReviewable() {
        return ANSWERS_REVIEWABLE.contains(this);
    }

    /** 答案是否已经入库。入库之后候选只读，"每道题恰好一条候选"从此不许再动。 */
    public boolean answersConfirmed() {
        return ANSWERS_CONFIRMED.contains(this);
    }

    /** 是否已经提交过。草稿不算提交，没提交的版本不该出现在教师的待批改列表里。 */
    public boolean submitted() {
        return this != DRAFT && this != UPLOADED;
    }

    /** 不会再变的历史状态：只读展示，不参与任何"下一步做什么"的判断。 */
    public boolean terminal() {
        return this == SUPERSEDED || this == RETURNED;
    }
}
