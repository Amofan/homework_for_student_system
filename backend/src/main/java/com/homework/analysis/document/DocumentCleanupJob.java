package com.homework.analysis.document;

import com.homework.analysis.submission.SubmissionVersionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 文件保留策略的执行者。
 *
 * <p>两条规则，都以「对象先删、元数据后改」为铁律：
 * <ol>
 *   <li><b>未完成上传</b>：所属文档仍是 {@code PENDING}/{@code FAILED} 且已满
 *       {@code draft-retention-days}（默认 7 天）的原始文件，直接丢弃。
 *       已经交上来的答卷不在此列 —— 见 {@link #findAbandonedUploads()}；</li>
 *   <li><b>已标记删除</b>：状态为 {@code DELETED} 的文件在 {@code deleted-grace-days}
 *       （默认 30 天）宽限期后删除对象内容。</li>
 * </ol>
 *
 * <p>两条规则都只删对象、不删数据库行：审计行与 SHA-256 会一直留着，
 * 这样"文件已经拿不回来"与"当时上传的是哪一份内容"可以同时成立。
 *
 * <p><b>尚未实现</b>：设计里的第三条规则「{@code SUPERSEDED} 的提交版本在作业
 * {@code COMPLETED} 后满 180 天」。V11 的 {@code submission_version} 表已经落地，
 * 条件已经拼得出来，但这条规则会真的删掉学生交过的东西，属于产品决策，
 * 留待明确后再补；宁可缺一条并标注，也不凭猜测写一条会误删答卷的规则。
 *
 * <p>每轮限量处理（{@code cleanup-batch-size}），避免积压时一次扫描把连接和对象存储配额占满。
 */
@Component
public class DocumentCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(DocumentCleanupJob.class);

    /** 学生还可编辑的版本状态。这些版本里的上传算"没交上来的东西"，可以按保留策略清理。 */
    private static final String EDITABLE_VERSION_STATUSES = SubmissionVersionStatus.editableNamesSql();

    private final JdbcClient jdbc;
    private final FileStorage storage;
    private final StorageProperties properties;
    private final Clock clock;

    DocumentCleanupJob(JdbcClient jdbc, FileStorage storage, StorageProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.properties = properties;
        this.clock = clock;
    }

    /** 定时入口。任何异常都在这里收住：一次失败不该让调度器停摆。 */
    @Scheduled(fixedDelayString = "${app.storage.cleanup-interval-ms}")
    public void runOnSchedule() {
        try {
            CleanupSummary summary = runOnce();
            if (summary.attempted() > 0) {
                log.info("文件清理完成：尝试 {} 个，成功 {} 个，失败 {} 个",
                    summary.attempted(), summary.purged(), summary.failed());
            }
        } catch (RuntimeException exception) {
            log.warn("文件清理任务执行失败，将在下个周期重试", exception);
        }
    }

    /** 供测试直接调用，不必等调度器。 */
    public CleanupSummary runOnce() {
        List<CleanupCandidate> candidates = findAbandonedUploads();
        candidates.addAll(findGraceExpiredMarkedFiles());

        int purged = 0;
        int failed = 0;
        for (CleanupCandidate candidate : candidates) {
            if (purge(candidate)) {
                purged++;
            } else {
                failed++;
            }
        }
        return new CleanupSummary(candidates.size(), purged, failed);
    }

    /**
     * 没交上来的上传。
     *
     * <p>学生答卷的上传也停在 {@code PENDING}（识别还没轮到）或 {@code FAILED}（照片太糊识别失败），
     * 所以光看文档状态分不出"学生扔下不管的草稿"和"已经交上来的答卷"。分不出就会删错：
     * 一份交上来放了一周的答卷，原图被当成废弃上传删掉，教师那边页面打不开，
     * 申诉时连学生交的是什么都说不出。
     *
     * <p>因此额外排除"所属版本已经提交"的上传。已提交包含 {@code SUPERSEDED} 与
     * {@code RETURNED}：被取代的那一版仍是"教师当时批的是哪一份"，退回的那一版是学生要回看的证据。
     * 留下来的只有还在编辑中的草稿 —— 它们被清理之后，提交时会被
     * {@code SUBMISSION_PAGE_FILE_MISSING} 挡住并提示重新上传，是能看见的失败。
     */
    private List<CleanupCandidate> findAbandonedUploads() {
        return jdbc.sql("""
                select sf.id, sf.storage_key
                from stored_file sf
                where sf.status = 'ACTIVE' and sf.purged_at is null
                  and sf.created_at < :cutoff
                  and exists (
                      select 1 from document_upload du
                      where du.original_file_id = sf.id
                        and du.status in ('PENDING', 'FAILED')
                        and du.deleted_at is null
                        and not exists (
                            select 1 from submission_version sv
                            where sv.id = du.submission_version_id
                              and sv.status not in (%s)))
                order by sf.id
                limit :batchSize
                """.formatted(EDITABLE_VERSION_STATUSES))
            .param("cutoff", cutoff(properties.draftRetentionDays()))
            .param("batchSize", properties.cleanupBatchSize())
            .query(DocumentCleanupJob::mapCandidate)
            .list();
    }

    private List<CleanupCandidate> findGraceExpiredMarkedFiles() {
        return jdbc.sql("""
                select sf.id, sf.storage_key
                from stored_file sf
                where sf.status = 'DELETED' and sf.purged_at is null
                  and sf.deleted_at is not null and sf.deleted_at < :cutoff
                order by sf.id
                limit :batchSize
                """)
            .param("cutoff", cutoff(properties.deletedGraceDays()))
            .param("batchSize", properties.cleanupBatchSize())
            .query(DocumentCleanupJob::mapCandidate)
            .list();
    }

    /**
     * 删除单个对象并回写元数据。
     *
     * <p>顺序反过来（先改状态、再删对象）会让删除失败的行永远躺在"已清理"之外：
     * 状态已经改过，下一轮查询再也找不到它，对象就永久留在存储里。
     */
    private boolean purge(CleanupCandidate candidate) {
        try {
            storage.delete(candidate.storageKey());
        } catch (IOException | RuntimeException exception) {
            // 只记 fileId，不记文件名与内容：它们可能包含学生信息。
            log.warn("删除对象失败，保留行状态待下轮重试：fileId={}", candidate.fileId(), exception);
            return false;
        }
        jdbc.sql("""
                update stored_file
                set status = 'DELETED', deleted_at = coalesce(deleted_at, current_timestamp(3)),
                    purged_at = current_timestamp(3), updated_at = current_timestamp(3)
                where id = :id
                """)
            .param("id", candidate.fileId())
            .update();
        return true;
    }

    private Timestamp cutoff(int retentionDays) {
        return Timestamp.from(clock.instant().minus(retentionDays, ChronoUnit.DAYS));
    }

    private static CleanupCandidate mapCandidate(java.sql.ResultSet rs, int rowNum)
        throws java.sql.SQLException {
        return new CleanupCandidate(rs.getLong("id"), rs.getString("storage_key"));
    }

    private record CleanupCandidate(long fileId, String storageKey) {}

    /** 一轮清理的结果。{@code attempted} 大于 0 才值得记日志，避免每小时刷一行"无事发生"。 */
    public record CleanupSummary(int attempted, int purged, int failed) {}
}
