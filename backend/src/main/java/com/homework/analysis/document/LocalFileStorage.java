package com.homework.analysis.document;

import com.homework.analysis.shared.error.DomainException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 本地受控目录实现，用于演示与自动化测试。
 *
 * <p>三个必须守住的点：
 * <ol>
 *   <li>每个键都要归一化到根目录之内，越界直接拒绝——存储键虽然由服务端生成，
 *       但一旦某天有代码把它拼上了外部输入，这里就是最后一道闸；</li>
 *   <li>先写临时文件再重命名，中途失败不会留下半个文件；重命名不带覆盖选项，
 *       键重复时必须失败而不是悄悄替换别人的对象；</li>
 *   <li>写完后回读校验哈希，不匹配就删掉对象再报错。</li>
 * </ol>
 */
public final class LocalFileStorage implements FileStorage {

    private static final int BUFFER_SIZE = 64 * 1024;

    private final Path root;

    public LocalFileStorage(StorageProperties properties) {
        this.root = Path.of(properties.localRoot()).toAbsolutePath().normalize();
    }

    @Override
    public StoredObject put(StorageWrite write) throws IOException {
        Path target = resolve(write.storageKey());
        Path parent = target.getParent();
        Files.createDirectories(parent);

        MessageDigest digest = sha256();
        Path temporary = Files.createTempFile(parent, "upload-", ".part");
        long size;
        try {
            try (OutputStream output = Files.newOutputStream(temporary,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                size = copyDigesting(write.source(), output, digest);
            }
            try {
                // 同目录改名，且刻意不带 REPLACE_EXISTING：存储键重复意味着调用方在复用键，
                // 那时应当失败并暴露问题，而不是覆盖掉别人的文件。
                Files.move(temporary, target);
            } catch (FileAlreadyExistsException exception) {
                throw new DomainException("STORAGE_KEY_CONFLICT", "存储键已存在，拒绝覆盖", HttpStatus.CONFLICT);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }

        String computed = HexFormat.of().formatHex(digest.digest());
        if (!computed.equals(sha256Of(target))) {
            // 磁盘写满、被截断之类的问题会让哈希对不上。此时对象已经在目录里了，
            // 必须删掉：留下它等于留一个"元数据说完整、内容已损坏"的文件。
            Files.deleteIfExists(target);
            throw new IOException("写入后校验失败，已删除对象：" + write.storageKey());
        }
        return new StoredObject(write.storageKey(), computed, size);
    }

    @Override
    public InputStream open(String storageKey) throws IOException {
        Path target = resolve(storageKey);
        if (!Files.isRegularFile(target)) {
            throw new DomainException("STORAGE_OBJECT_MISSING", "文件内容不存在", HttpStatus.NOT_FOUND);
        }
        return Files.newInputStream(target);
    }

    @Override
    public void delete(String storageKey) throws IOException {
        Files.deleteIfExists(resolve(storageKey));
    }

    /**
     * 把存储键解析到根目录之内。
     *
     * <p>先归一化再判断前缀：{@code ../../etc/passwd}、绝对路径、Windows 盘符路径
     * 归一化后都会跑到根目录之外，从而被这一步拒绝。仅靠"文件名里没有 .."是不够的。
     */
    private Path resolve(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new DomainException("STORAGE_KEY_INVALID", "存储键不能为空");
        }
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new DomainException("STORAGE_KEY_INVALID", "存储键越出受控目录");
        }
        return target;
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

    private static String sha256Of(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 是 JDK 必须提供的算法，走到这里说明运行环境本身不完整。
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }
}
