package com.homework.analysis.document;

import com.homework.analysis.shared.error.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileStorageTest {

    @TempDir Path root;

    private LocalFileStorage storage;

    @BeforeEach
    void setUp() {
        storage = new LocalFileStorage(properties(root));
    }

    @Test
    void 写入后回传的哈希与长度就是内容的哈希与长度() throws IOException {
        byte[] content = "私有对象存储的第一份内容".getBytes(StandardCharsets.UTF_8);

        FileStorage.StoredObject stored = storage.put(write("teachers/11/uploads/a.pdf", content));

        assertThat(stored.storageKey()).isEqualTo("teachers/11/uploads/a.pdf");
        assertThat(stored.sizeBytes()).isEqualTo(content.length);
        assertThat(stored.sha256()).isEqualTo(sha256Of(content));
        assertThat(Files.readAllBytes(root.resolve("teachers/11/uploads/a.pdf"))).isEqualTo(content);
    }

    @Test
    void 读回的内容与写入一致() throws IOException {
        byte[] content = "page-bytes".getBytes(StandardCharsets.UTF_8);
        storage.put(write("teachers/11/uploads/b.png", content));

        try (InputStream input = storage.open("teachers/11/uploads/b.png")) {
            assertThat(input.readAllBytes()).isEqualTo(content);
        }
    }

    /**
     * 同一个键第二次写入必须失败，且原内容不被替换。
     *
     * <p>存储键由服务端生成，出现重复只可能是调用方在复用键。此时覆盖等于把别人的文件
     * 悄悄换成另一份内容，而所有引用它的元数据还指向旧的那一份。
     */
    @Test
    void 重复写入同一个键被拒绝且不覆盖原内容() throws IOException {
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        byte[] replacement = "replacement".getBytes(StandardCharsets.UTF_8);
        storage.put(write("teachers/11/uploads/c.pdf", original));

        assertThatThrownBy(() -> storage.put(write("teachers/11/uploads/c.pdf", replacement)))
            .isInstanceOf(DomainException.class)
            .extracting(exception -> ((DomainException) exception).code())
            .isEqualTo("STORAGE_KEY_CONFLICT");

        try (InputStream input = storage.open("teachers/11/uploads/c.pdf")) {
            assertThat(input.readAllBytes()).isEqualTo(original);
        }
    }

    @Test
    void 失败的写入不会留下临时文件() throws IOException {
        storage.put(write("teachers/11/uploads/d.pdf", "x".getBytes(StandardCharsets.UTF_8)));
        storage.put(write("teachers/11/uploads/e.pdf", "y".getBytes(StandardCharsets.UTF_8)));

        try (var entries = Files.list(root.resolve("teachers/11/uploads"))) {
            assertThat(entries.map(path -> path.getFileName().toString()))
                .containsExactlyInAnyOrder("d.pdf", "e.pdf");
        }
    }

    /**
     * 越界的键必须拒绝。
     *
     * <p>四种写法都要覆盖：相对穿越、绝对路径、Windows 盘符式路径、以及空键。
     * 只挡 {@code ..} 是不够的——绝对路径连 {@code ..} 都不需要。
     */
    @Test
    void 越界的存储键被拒绝() {
        assertThatThrownBy(() -> storage.open("../outside.txt"))
            .isInstanceOf(DomainException.class)
            .extracting(exception -> ((DomainException) exception).code())
            .isEqualTo("STORAGE_KEY_INVALID");

        assertThatThrownBy(() -> storage.open("teachers/../../outside.txt"))
            .isInstanceOf(DomainException.class)
            .extracting(exception -> ((DomainException) exception).code())
            .isEqualTo("STORAGE_KEY_INVALID");

        assertThatThrownBy(() -> storage.open(root.getParent().resolve("outside.txt").toString()))
            .isInstanceOf(DomainException.class);

        assertThatThrownBy(() -> storage.open("C:\\Windows\\win.ini"))
            .isInstanceOf(DomainException.class);

        assertThatThrownBy(() -> storage.open("  "))
            .isInstanceOf(DomainException.class);
    }

    @Test
    void 读取不存在的对象给出404语义() {
        assertThatThrownBy(() -> storage.open("teachers/11/uploads/missing.pdf"))
            .isInstanceOf(DomainException.class)
            .extracting(exception -> ((DomainException) exception).code())
            .isEqualTo("STORAGE_OBJECT_MISSING");
    }

    @Test
    void 删除是幂等的() throws IOException {
        storage.put(write("teachers/11/uploads/f.pdf", "z".getBytes(StandardCharsets.UTF_8)));

        storage.delete("teachers/11/uploads/f.pdf");
        storage.delete("teachers/11/uploads/f.pdf");

        assertThat(Files.exists(root.resolve("teachers/11/uploads/f.pdf"))).isFalse();
    }

    /** 长度未知的流也要能写：上传路径上拿不到 Content-Length 是常态。 */
    @Test
    void 长度未知的流也能写入() throws IOException {
        byte[] content = "streamed".getBytes(StandardCharsets.UTF_8);

        FileStorage.StoredObject stored = storage.put(new FileStorage.StorageWrite(
            "teachers/11/uploads/g.pdf", "application/pdf", -1, new ByteArrayInputStream(content)));

        assertThat(stored.sizeBytes()).isEqualTo(content.length);
    }

    private static FileStorage.StorageWrite write(String key, byte[] content) {
        return new FileStorage.StorageWrite(key, "application/pdf", content.length,
            new ByteArrayInputStream(content));
    }

    private static StorageProperties properties(Path localRoot) {
        return new StorageProperties(StorageProperties.Provider.LOCAL, localRoot.toString(),
            "", "", "", false, 25_000_000L, 104_857_600L, 40, 40_000_000L,
            200, false, 200, 3_600_000L, 7, 30);
    }

    private static String sha256Of(byte[] content) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IOException(exception);
        }
    }
}
