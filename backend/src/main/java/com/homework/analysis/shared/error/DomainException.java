package com.homework.analysis.shared.error;

import org.springframework.http.HttpStatus;

public class DomainException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public DomainException(String code, String message) {
        this(code, message, HttpStatus.BAD_REQUEST);
    }

    public DomainException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    /**
     * 保留原始异常以便排障。对外消息仍保持稳定且不含敏感内容，
     * 根因只通过 cause 暴露给日志，不进入 HTTP 响应体。
     */
    public DomainException(String code, String message, Throwable cause) {
        this(code, message, HttpStatus.BAD_REQUEST, cause);
    }

    /** 保留原始异常的完整构造器；对外仍只暴露 code 与 message。 */
    public DomainException(String code, String message, HttpStatus status, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }
}
