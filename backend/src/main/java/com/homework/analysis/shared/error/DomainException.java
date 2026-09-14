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
