package com.homework.analysis.ocr;

import com.homework.analysis.document.FileStorage;
import com.homework.analysis.document.PdfPageRenderer;
import com.homework.analysis.shared.error.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * OCR 任务编排。
 *
 * <p>职责边界：领取任务、准备页面图像、调用 {@link OcrProvider}、把结果落成"候选数据"。
 * 它**不写** {@code question}、{@code student_answer} 或任何成绩——那些只能由教师在
 * 校对界面确认后由各自的业务服务写入。
 *
 * <p>失败分两类，这个区分是有限重试能成立的前提：
 * <ul>
 *   <li><b>可重试</b>：服务不可用、超时等一次性故障 → {@code RETRY_WAIT}，退避后再来；</li>
 *   <li><b>不可重试</b>：契约不合法、请求超限、输入文件损坏 → 直接 {@code FAILED}。</li>
 * </ul>
 * 把契约错误也拿去重试，只会把"上游有 bug"伪装成"偶发故障"，拖到人工兜底时才暴露。
 */
@Service
public class OcrTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(OcrTaskWorker.class);

    /** 候选任务可能被其它线程抢先领取，最多重新挑选若干轮。 */
    private static final int MAX_CLAIM_ROUNDS = 3;

    /** 只对这类错误做退避重试；其余失败重试也不会变好。 */
    private static final Set<String> RETRYABLE_CODES = Set.of("OCR_UNAVAILABLE");

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_NEEDS_REVIEW = "NEEDS_REVIEW";
    private static final String STATUS_RETRY_WAIT = "RETRY_WAIT";
    private static final String STATUS_FAILED = "FAILED";

    private final JdbcClient jdbc;
    private final FileStorage storage;
    private final PdfPageRenderer pdfRenderer;
    private final OcrProvider provider;
    private final OcrProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    /** 可空：整卷导入不提供模板，那时识别按卷面题号分组。 */
    private final OcrTemplateProvider templateProvider;

    OcrTaskWorker(JdbcClient jdbc, FileStorage storage, PdfPageRenderer pdfRenderer,
                  OcrProvider provider, OcrProperties properties, ObjectMapper objectMapper, Clock clock,
                  Optional<OcrTemplateProvider> templateProvider) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.pdfRenderer = pdfRenderer;
        this.provider = provider;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.templateProvider = templateProvider.orElse(null);
    }

    /**
     * 处理指定文档下的一个 OCR 任务。
     *
     * @return 本次处理的结果；没有可处理的任务时 {@code processed=false}
     */
    public OcrTaskProcessResult processOne(long teacherId, long documentId) {
        requireOwnedDocument(teacherId, documentId);
        OcrTaskData task = claimNextTask(documentId);
        if (task == null) {
            return new OcrTaskProcessResult(false, null, "EMPTY", "没有可处理的 OCR 任务");
        }
        Instant startedAt = clock.instant();
        try {
            OcrRequest request = buildRequest(task);
            OcrResult result = provider.analyze(request);
            String rawResult = serialize(result);
            long durationMs = Duration.between(startedAt, clock.instant()).toMillis();

            jdbc.sql("""
                update ocr_task set status = :status, engine = :engine, model_version = :modelVersion,
                    raw_output = :rawOutput, duration_ms = :durationMs,
                    finished_at = current_timestamp(3), failure_code = null,
                    next_attempt_at = null, updated_at = current_timestamp(3)
                where id = :id
                """)
                .param("status", STATUS_NEEDS_REVIEW)
                .param("engine", result.engine())
                .param("modelVersion", result.modelVersion())
                .param("rawOutput", rawResult)
                .param("durationMs", durationMs)
                .param("id", task.taskId())
                .update();
            // 页面数在这里回写：文档状态从 PENDING 变成"等教师校对"。
            // 注意不把文档标成 CONFIRMED——确认只能由教师在校对界面做出。
            jdbc.sql("""
                update document_upload set status = :status, page_count = :pageCount,
                    current_ocr_version = :processingVersion, failure_reason_code = null,
                    updated_at = current_timestamp(3)
                where id = :documentId
                """)
                .param("status", STATUS_NEEDS_REVIEW)
                .param("pageCount", result.pages().size())
                .param("processingVersion", task.processingVersion())
                .param("documentId", task.documentId())
                .update();
            return new OcrTaskProcessResult(true, task.taskId(), STATUS_NEEDS_REVIEW,
                "识别完成，等待教师校对");
        } catch (Exception exception) {
            return handleFailure(task, exception);
        }
    }

    /**
     * 页面图像准备。
     *
     * <p>PDF 先按配置 DPI 渲染成页面图再送识别，而不是把整份 PDF 扔给 OCR 服务：
     * 页面图是后续裁剪题图与答案图所依据的那一份资产，OCR 与裁剪必须基于同一份像素，
     * 否则识别框与裁剪图会错位。
     */
    private OcrRequest buildRequest(OcrTaskData task) {
        byte[] original = readOriginal(task);
        List<OcrRequest.Page> pages = new ArrayList<>();
        if ("application/pdf".equals(task.mimeType())) {
            try {
                for (PdfPageRenderer.PageImage page : pdfRenderer.render(original)) {
                    pages.add(new OcrRequest.Page(page.pageNo(), encode(page.png())));
                }
            } catch (IOException exception) {
                throw new DomainException("OCR_INPUT_INVALID", "上传的 PDF 无法解析",
                    HttpStatus.UNPROCESSABLE_ENTITY, exception);            }
        } else {
            pages.add(new OcrRequest.Page(1, encode(original)));
        }
        if (pages.isEmpty()) {
            throw new DomainException("OCR_INPUT_INVALID", "文档没有可识别的页面",
                HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return new OcrRequest(task.documentKind(), task.processingVersion(), pages, templateOf(task));
    }

    /**
     * 这次识别要带的模板。
     *
     * <p>模板取不到（没有提供者、这份文档不属于任何答卷、模板一页都没有）时返回 {@code null}：
     * 识别照常进行，只是区域不带题号，由教师在校对界面里手工归类。让识别因为"没有模板"失败
     * 会把一份能看的答卷卡死在一个教师无法补救的错误上。
     */
    private OcrRequest.Template templateOf(OcrTaskData task) {
        if (templateProvider == null) {
            return null;
        }
        return templateProvider.templateFor(task.documentId())
            .filter(template -> template.pages() != null && !template.pages().isEmpty())
            .orElse(null);
    }

    private byte[] readOriginal(OcrTaskData task) {
        try (var input = storage.open(task.storageKey())) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new DomainException("OCR_INPUT_INVALID", "原始文件内容不可读取",
                HttpStatus.UNPROCESSABLE_ENTITY, exception);
        }
    }

    private OcrTaskProcessResult handleFailure(OcrTaskData task, Exception exception) {
        int attempts = task.attemptCount() + 1;
        String failureCode = errorCode(exception);
        boolean retryable = RETRYABLE_CODES.contains(failureCode) && attempts < properties.maxAttempts();

        if (!retryable) {
            // 只记 taskId 与错误码，不记识别文本与文件内容。
            log.warn("OCR 任务失败并标记为需要人工介入：taskId={} documentId={} 错误码={} 第 {} 次尝试",
                task.taskId(), task.documentId(), failureCode, attempts, exception);
            jdbc.sql("""
                update ocr_task set status = :status, failure_code = :failureCode, attempt_count = :attempts,
                    finished_at = current_timestamp(3), next_attempt_at = null, updated_at = current_timestamp(3)
                where id = :id
                """)
                .param("status", STATUS_FAILED)
                .param("failureCode", failureCode)
                .param("attempts", attempts)
                .param("id", task.taskId())
                .update();
            jdbc.sql("""
                update document_upload set status = 'FAILED', failure_reason_code = :failureCode,
                    updated_at = current_timestamp(3)
                where id = :documentId
                """)
                .param("failureCode", failureCode)
                .param("documentId", task.documentId())
                .update();
            return new OcrTaskProcessResult(true, task.taskId(), STATUS_FAILED, "识别失败，需要人工处理");
        }

        Instant nextAttempt = clock.instant().plus(backoff(attempts));
        log.warn("OCR 任务暂时失败，安排第 {} 次重试：taskId={} 错误码={}",
            attempts + 1, task.taskId(), failureCode, exception);
        jdbc.sql("""
            update ocr_task set status = :status, failure_code = :failureCode, attempt_count = :attempts,
                next_attempt_at = :nextAttempt, updated_at = current_timestamp(3)
            where id = :id
            """)
            .param("status", STATUS_RETRY_WAIT)
            .param("failureCode", failureCode)
            .param("attempts", attempts)
            .param("nextAttempt", java.sql.Timestamp.from(nextAttempt))
            .param("id", task.taskId())
            .update();
        return new OcrTaskProcessResult(true, task.taskId(), STATUS_RETRY_WAIT, "识别服务不可用，任务已安全保留");
    }

    /** 与 AI 评分任务共用同一套退避梯度，运维只需要记一种节奏。 */
    private static Duration backoff(int attempts) {
        return switch (attempts) {
            case 1 -> Duration.of(30, ChronoUnit.SECONDS);
            case 2 -> Duration.of(2, ChronoUnit.MINUTES);
            default -> Duration.of(10, ChronoUnit.MINUTES);
        };
    }

    private static String errorCode(Exception exception) {
        if (exception instanceof DomainException domain) {
            return domain.code();
        }
        return "OCR_INTERNAL";
    }

    private void requireOwnedDocument(long teacherId, long documentId) {
        Integer owned = jdbc.sql("""
                select count(*) from document_upload
                where id = :documentId and teacher_id = :teacherId and deleted_at is null
                """)
            .param("documentId", documentId)
            .param("teacherId", teacherId)
            .query(Integer.class)
            .single();
        if (owned == null || owned == 0) {
            throw new DomainException("DOCUMENT_NOT_FOUND", "文档不存在", HttpStatus.NOT_FOUND);
        }
    }

    /**
     * 原子领取一个到期任务。
     *
     * <p>领取用带旧状态条件的更新：只有影响行数为 1 的线程可以继续，避免两个线程对同一份文档
     * 重复调用 OCR 服务（识别很贵，重复调用也不产生第二份可用结果）。
     */
    private OcrTaskData claimNextTask(long documentId) {
        for (int round = 0; round < MAX_CLAIM_ROUNDS; round++) {
            List<OcrTaskData> candidates = jdbc.sql("""
                    select t.id as task_id, t.document_id, t.document_kind, t.processing_version, t.attempt_count,
                           sf.storage_key, sf.mime_type
                    from ocr_task t
                    join document_upload du on du.id = t.document_id
                    join stored_file sf on sf.id = du.original_file_id
                    where t.document_id = :documentId
                      and t.status in ('PENDING', 'RETRY_WAIT')
                      and (t.next_attempt_at is null or t.next_attempt_at <= current_timestamp(3))
                    order by t.id limit 1
                    """)
                .param("documentId", documentId)
                .query((rs, rowNum) -> new OcrTaskData(
                    rs.getLong("task_id"), rs.getLong("document_id"), rs.getString("document_kind"),
                    rs.getInt("processing_version"), rs.getInt("attempt_count"),
                    rs.getString("storage_key"), rs.getString("mime_type")))
                .list();
            if (candidates.isEmpty()) {
                return null;
            }
            OcrTaskData candidate = candidates.getFirst();
            if (markRunning(candidate.taskId()) == 1) {
                return candidate;
            }
        }
        return null;
    }

    private int markRunning(long taskId) {
        return jdbc.sql("""
                update ocr_task set status = 'RUNNING', claimed_at = current_timestamp(3),
                    started_at = current_timestamp(3), attempt_count = attempt_count + 1,
                    updated_at = current_timestamp(3)
                where id = :id
                  and status in ('PENDING', 'RETRY_WAIT')
                  and (next_attempt_at is null or next_attempt_at <= current_timestamp(3))
                """)
            .param("id", taskId)
            .update();
    }

    private String serialize(OcrResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JacksonException exception) {
            throw new DomainException("OCR_CONTRACT_INVALID", "识别结果无法序列化",
                HttpStatus.BAD_GATEWAY, exception);
        }
    }

    private static String encode(byte[] content) {
        return Base64.getEncoder().encodeToString(content);
    }

    private record OcrTaskData(long taskId, long documentId, String documentKind,
                               int processingVersion, int attemptCount,
                               String storageKey, String mimeType) {}
}
