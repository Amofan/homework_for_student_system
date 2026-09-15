package com.homework.analysis.exercise;

import com.homework.analysis.analytics.AnalyticsService;
import com.homework.analysis.analytics.KnowledgeMastery;
import com.homework.analysis.assignment.AssignmentService;
import com.homework.analysis.assignment.AssignmentView;
import com.homework.analysis.classroom.ClassroomService;
import com.homework.analysis.classroom.ClassroomView;
import com.homework.analysis.shared.error.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toSet;

/**
 * 分层练习。层级只由来源作业里"教师已确认"的评分结果决定，
 * 未确认的模型建议不参与分层——否则模型一改，练习单就跟着变。
 *
 * <p>组题完全确定：不查时间、不取随机数、不调用模型，只用现有题库。
 * 同一份来源作业与同一份已确认画像，永远得到同一张练习单。
 */
@Service
public class ExerciseService {
    /** 掌握度低于该值的知识点进入基础巩固层。 */
    static final double FOUNDATION_CEILING = 0.60;
    /** 掌握度不超过该值的知识点进入方法纠错层，高于该值才进入综合提升层。 */
    static final double CORRECTION_CEILING = 0.80;

    private final ExerciseRepository repository;
    private final ClassroomService classrooms;
    private final AssignmentService assignments;
    private final AnalyticsService analytics;
    private final ExerciseDocumentExporter exporter;

    ExerciseService(ExerciseRepository repository, ClassroomService classrooms, AssignmentService assignments,
                    AnalyticsService analytics, ExerciseDocumentExporter exporter) {
        this.repository = repository;
        this.classrooms = classrooms;
        this.assignments = assignments;
        this.analytics = analytics;
        this.exporter = exporter;
    }

    @Transactional
    public ExerciseSetView generate(long teacherId, ExerciseGenerationCommand command) {
        ClassroomView classroom = classrooms.get(teacherId, command.classId());
        AssignmentView assignment = assignments.requireOwned(teacherId, command.sourceAssignmentId());
        if (assignment.classId() != command.classId()) {
            throw new DomainException("EXERCISE_ASSIGNMENT_MISMATCH", "来源作业不属于所选班级");
        }
        List<KnowledgeMastery> mastery = analytics.mastery(teacherId, command.classId(), command.sourceAssignmentId());
        if (mastery.isEmpty()) {
            throw new DomainException("EXERCISE_NO_CONFIRMED_RESULTS",
                "该来源作业还没有教师确认过的评分结果，无法生成分层练习");
        }
        List<ExerciseItemView> items = selectItems(repository.candidates(command.sourceAssignmentId()), mastery);
        long exerciseId = repository.insertSet(teacherId, command.classId(), command.sourceAssignmentId(),
            defaultTitle(command, classroom, assignment));
        items.forEach(item -> repository.insertItem(exerciseId, item));
        return requireOwned(teacherId, exerciseId);
    }

    public List<ExerciseSetView> list(long teacherId, long classId) {
        classrooms.get(teacherId, classId);
        return repository.listOwned(teacherId, classId);
    }

    public ExerciseSetView requireOwned(long teacherId, long exerciseId) {
        return repository.findOwned(teacherId, exerciseId)
            .orElseThrow(() -> new DomainException("EXERCISE_NOT_FOUND", "练习单不存在", HttpStatus.NOT_FOUND));
    }

    @Transactional
    public ExerciseSetView approve(long teacherId, long exerciseId) {
        ExerciseSetView current = requireOwned(teacherId, exerciseId);
        if (current.status() != ExerciseStatus.DRAFT) {
            throw new DomainException("EXERCISE_NOT_DRAFT", "只有草稿状态的练习单可以确认", HttpStatus.CONFLICT);
        }
        repository.markApproved(teacherId, exerciseId);
        return requireOwned(teacherId, exerciseId);
    }

    public ExportedDocument exportDocx(long teacherId, long exerciseId) {
        ExerciseSetView exercise = requireOwned(teacherId, exerciseId);
        if (exercise.status() != ExerciseStatus.APPROVED) {
            throw new DomainException("EXERCISE_NOT_APPROVED", "练习单确认后才能导出 Word", HttpStatus.CONFLICT);
        }
        return exporter.write(exercise);
    }

    private static String defaultTitle(ExerciseGenerationCommand command, ClassroomView classroom,
                                       AssignmentView assignment) {
        String requested = command.title() == null ? "" : command.title().trim();
        return requested.isEmpty()
            ? classroom.name() + " · " + assignment.title() + " 分层练习"
            : requested;
    }

    /**
     * 只出主知识点落在本层的题：本层题不够就少出，绝不拿别层的题来凑。
     *
     * <p>拿别层的题补足会让三层的实际难度趋同——巩固层本该给薄弱学生降难度，
     * 却被塞进提升层的题，分层就名存实亡。题量不齐只是排版问题，交给
     * {@link ExerciseSetView#shortageNotices} 说明即可。
     *
     * <p>各层目标集合天然互斥：{@link #tierOf} 是函数，一个知识点只属于一层；
     * 每道题又只有一个主知识点。所以不存在一层抢走另一层题目的可能，
     * 也就不需要去重。
     */
    private static List<ExerciseItemView> selectItems(List<ExerciseCandidate> candidates,
                                                      List<KnowledgeMastery> mastery) {
        Map<ExerciseTier, Set<Long>> targetKnowledgePoints = mastery.stream()
            .collect(groupingBy(entry -> tierOf(entry.masteryRatio()),
                mapping(KnowledgeMastery::knowledgePointId, toSet())));

        Map<ExerciseTier, List<ExerciseCandidate>> chosen = new EnumMap<>(ExerciseTier.class);
        for (ExerciseTier tier : ExerciseTier.values()) {
            Set<Long> targets = targetKnowledgePoints.getOrDefault(tier, Set.of());
            chosen.put(tier, ranked(candidates, tier).stream()
                .filter(candidate -> targets.contains(candidate.knowledgePointId()))
                .limit(ExerciseSetView.ITEMS_PER_TIER)
                .toList());
        }
        return toItems(chosen);
    }

    private static List<ExerciseItemView> toItems(Map<ExerciseTier, List<ExerciseCandidate>> chosen) {
        List<ExerciseItemView> items = new ArrayList<>();
        for (ExerciseTier tier : ExerciseTier.values()) {
            List<ExerciseCandidate> picked = chosen.getOrDefault(tier, List.of());
            for (int index = 0; index < picked.size(); index++) {
                ExerciseCandidate candidate = picked.get(index);
                // 评分细则留空：本节只负责排版顺序，题面与答案在写库后由仓储统一读回，
                // 导出时用的就是读回的那份，不在这里另拼一套。
                items.add(new ExerciseItemView(tier, index + 1, candidate.questionId(), candidate.questionCode(),
                    candidate.content(), candidate.totalScore(), candidate.difficulty(),
                    candidate.knowledgePointId(), candidate.knowledgePointName(), candidate.standardAnswer(),
                    List.of()));
            }
        }
        return List.copyOf(items);
    }

    private static List<ExerciseCandidate> ranked(List<ExerciseCandidate> candidates, ExerciseTier tier) {
        return candidates.stream()
            .sorted(Comparator.comparingInt((ExerciseCandidate candidate) -> tier.difficultyRank(candidate.difficulty()))
                .thenComparing(ExerciseCandidate::questionCode))
            .toList();
    }

    /** 掌握度分段：低于 0.60 归基础巩固，0.60 至 0.80 归方法纠错，高于 0.80 归综合提升。 */
    static ExerciseTier tierOf(double masteryRatio) {
        if (masteryRatio < FOUNDATION_CEILING) return ExerciseTier.FOUNDATION;
        if (masteryRatio <= CORRECTION_CEILING) return ExerciseTier.CORRECTION;
        return ExerciseTier.IMPROVEMENT;
    }
}
