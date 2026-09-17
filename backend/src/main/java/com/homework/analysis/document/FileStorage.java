package com.homework.analysis.document;

import java.io.IOException;
import java.io.InputStream;

/**
 * 私有文件存储。
 *
 * <p>业务代码只认这三个方法，不关心对象落在本地磁盘还是对象存储；演示与测试用
 * {@link LocalFileStorage}，正式环境用 {@link S3FileStorage}。
 *
 * <p>存储键由服务端生成，调用方不得传用户文件名当键。适配器必须保证：
 * 写入是"全有或全无"、不覆盖已有对象、并回传实际落盘内容的 SHA-256。
 */
public interface FileStorage {

    StoredObject put(StorageWrite write) throws IOException;

    InputStream open(String storageKey) throws IOException;

    void delete(String storageKey) throws IOException;

    /** 写入请求。{@code contentLength} 允许为 -1（长度未知的流）。 */
    record StorageWrite(String storageKey, String contentType, long contentLength, InputStream source) {}

    /**
     * 写入结果。
     *
     * <p>哈希与字节数由适配器在写入过程中算出并**回读校验**，不是从请求里抄来的：
     * 只有这一份才是"对象存储里真实存在的内容"的指纹。
     */
    record StoredObject(String storageKey, String sha256, long sizeBytes) {}
}
