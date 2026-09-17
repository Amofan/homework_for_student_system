package com.homework.analysis.document;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文件存储与上传上限。
 *
 * <p>上限全部走配置而非常量：这些数字（25 MiB / 40 页 / 4000 万像素）是部署方的容量决策，
 * 换一台机器、换一个对象存储就可能要调。测试用 {@code @DynamicPropertySource} 把它们调小，
 * 就不必为了触发"像素炸弹"分支去构造一张 4000 万像素的真图。
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
    Provider provider,
    String localRoot,
    String bucket,
    String region,
    String endpoint,
    boolean pathStyleAccess,
    long maxFileBytes,
    long maxSubmissionBytes,
    int maxPages,
    long maxPixelsPerPage,
    int renderDpi,
    boolean cleanupEnabled,
    int cleanupBatchSize,
    long cleanupIntervalMs,
    int draftRetentionDays,
    int deletedGraceDays) {

    public enum Provider {
        /** 本地受控目录，用于演示与自动化测试。 */
        LOCAL,
        /** 私有 S3 兼容对象存储，用于正式环境。 */
        S3
    }

    /**
     * 启动期校验 S3 参数。
     *
     * <p>缺参数时直接失败，而不是退回本地目录：把正式环境的文件悄悄写到某个容器本地磁盘上，
     * 比启动失败危险得多——文件会在下次发布时无声消失。
     */
    public void requireBucketAndRegion() {
        if (isBlank(bucket) || isBlank(region)) {
            throw new IllegalStateException(
                "provider=S3 时必须配置 app.storage.bucket 与 app.storage.region");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
