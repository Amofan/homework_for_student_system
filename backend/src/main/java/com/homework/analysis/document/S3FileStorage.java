package com.homework.analysis.document;

import com.homework.analysis.shared.error.DomainException;
import org.springframework.http.HttpStatus;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 私有 S3 兼容对象存储实现。
 *
 * <p>三条设计约束写死在代码里，不做成配置项：
 * <ul>
 *   <li>桶必须私有：本类从不设置 ACL，对象可见性只由桶策略决定；</li>
 *   <li>不生成公开 URL：对外读取一律走后端鉴权流；</li>
 *   <li>写入必须先落到本地临时文件：{@code putObject} 需要已知长度，而且回传的哈希
 *       必须是"我们真正提交上去的那些字节"的哈希。</li>
 * </ul>
 *
 * <p>本类在演示与自动化测试中不会被实例化——那边的 provider 是 LOCAL。
 */
public final class S3FileStorage implements FileStorage {

    private static final int BUFFER_SIZE = 64 * 1024;

    private final S3Client client;
    private final String bucket;

    public S3FileStorage(StorageProperties properties) {
        properties.requireBucketAndRegion();
        this.bucket = properties.bucket();
        var builder = S3Client.builder()
            .region(Region.of(properties.region()))
            // 自建 MinIO / Ceph 这类兼容存储通常不支持虚拟主机风格，需要路径风格访问。
            .serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(properties.pathStyleAccess())
                .build());
        if (properties.endpoint() != null && !properties.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        this.client = builder.build();
    }

    @Override
    public StoredObject put(StorageWrite write) throws IOException {
        Path temporary = Files.createTempFile("upload-", ".part");
        try {
            MessageDigest digest = sha256();
            long size;
            try (OutputStream output = Files.newOutputStream(temporary)) {
                size = copyDigesting(write.source(), output, digest);
            }
            String computed = HexFormat.of().formatHex(digest.digest());
            client.putObject(request -> request
                .bucket(bucket)
                .key(write.storageKey())
                .contentType(write.contentType())
                .build(), RequestBody.fromFile(temporary));
            return new StoredObject(write.storageKey(), computed, size);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public InputStream open(String storageKey) throws IOException {
        try {
            ResponseInputStream<GetObjectResponse> stream = client.getObject(
                request -> request.bucket(bucket).key(storageKey).build());
            return stream;
        } catch (NoSuchKeyException exception) {
            throw new DomainException("STORAGE_OBJECT_MISSING", "文件内容不存在", HttpStatus.NOT_FOUND, exception);
        }
    }

    /** S3 的删除本身是幂等的：对象不存在也返回成功，因此这里不需要先判存在。 */
    @Override
    public void delete(String storageKey) throws IOException {
        client.deleteObject(request -> request.bucket(bucket).key(storageKey).build());
    }

    private static long copyDigesting(InputStream source, OutputStream output, MessageDigest digest)
        throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        int read;
        while ((read = source.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
            output.write(buffer, 0, read);
            total += read;
        }
        return total;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }
}
