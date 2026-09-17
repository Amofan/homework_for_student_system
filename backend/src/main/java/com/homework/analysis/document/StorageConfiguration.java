package com.homework.analysis.document;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 存储实现的装配。
 *
 * <p>{@code FileStorage} 按 {@code app.storage.provider} 二选一。两个候选都注册会让注入点
 * 变成"有两个候选 Bean"的启动失败；只注册一个则让"选错了实现"这件事在启动期就暴露。
 */
@Configuration
class StorageConfiguration {

    @Bean
    @ConditionalOnProperty(name = "app.storage.provider", havingValue = "LOCAL", matchIfMissing = true)
    FileStorage localStorage(StorageProperties properties) {
        return new LocalFileStorage(properties);
    }

    @Bean
    @ConditionalOnProperty(name = "app.storage.provider", havingValue = "S3")
    FileStorage s3Storage(StorageProperties properties) {
        return new S3FileStorage(properties);
    }

    @Bean
    PdfPageRenderer pdfPageRenderer(StorageProperties properties) {
        return new PdfPageRenderer(properties);
    }
}

/**
 * 只在启用清理任务时开启调度。
 *
 * <p>自动化测试用 {@code app.storage.cleanup-enabled=false} 关掉它：后台任务会在断言中途
 * 删掉文件，制造出"偶发失败"这种最难查的问题。测试直接调用 {@code runOnce()}。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.storage.cleanup-enabled", havingValue = "true", matchIfMissing = true)
class StorageSchedulingConfiguration {
}
