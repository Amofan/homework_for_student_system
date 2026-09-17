package com.homework.analysis.ocr;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * OCR 服务与任务编排配置。
 *
 * <p>默认 {@code enabled=false}：OCR 服务是独立进程，本机不一定随时在跑。
 * 关掉时不会让任务直接失败，而是走"服务不可用"的有限重试路径，避免一整批作业在
 * 服务还没起来的时候被标记成永久失败。
 */
@ConfigurationProperties(prefix = "app.ocr")
public record OcrProperties(
    boolean enabled,
    String baseUrl,
    String analyzePath,
    /** 内部调用令牌。只放在请求头里，不写日志。 */
    String token,
    int timeoutSeconds,
    /** 有限重试的总次数上限，超过即判定为需要人工介入。 */
    int maxAttempts,
    /**
     * 单次请求体的字节上限。
     *
     * <p>页面图按 base64 随请求发送（服务端无对象存储凭据，也不共享业务库），
     * base64 会把体积放大约三分之一。这里设一个上限，超了就在本地失败并给出明确错误码，
     * 而不是发一个几百 MB 的请求出去等超时。
     */
    long maxRequestBytes,
    /** 处理版本：与文件哈希、文档类型一起构成 OCR 幂等键，模型或参数升级时递增。 */
    int processingVersion,
    String modelVersion) {

    public Duration timeout() {
        return Duration.ofSeconds(timeoutSeconds);
    }
}
