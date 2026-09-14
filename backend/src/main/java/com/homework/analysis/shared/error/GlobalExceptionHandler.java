package com.homework.analysis.shared.error;

import com.homework.analysis.shared.api.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(DomainException.class)
    ResponseEntity<ApiResponse<Void>> handleDomain(DomainException exception) {
        return ResponseEntity.status(exception.status())
            .body(ApiResponse.failure(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .orElse("请求参数无效");
        return ResponseEntity.badRequest().body(ApiResponse.failure("VALIDATION_FAILED", message));
    }
}
