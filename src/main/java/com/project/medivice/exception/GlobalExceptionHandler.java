package com.project.medivice.exception;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst()
                .orElse("입력값이 올바르지 않습니다.");
        return build(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException ex) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
    }

    /** OcrService의 입력 검증(빈 파일·용량 초과·지원하지 않는 형식)이 여기로 떨어진다. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxUploadSize(org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        return build(HttpStatus.BAD_REQUEST, "이미지 용량이 너무 큽니다(최대 10MB).");
    }

    /**
     * DTO의 Bean Validation이 놓친 값이 DB CHECK 제약까지 가서 막힐 때의 방어선 — 원래는
     * @Min/@Max 같은 검증으로 여기까지 오지 않아야 한다(예: MedicationCreateRequest.timesPerDay).
     * 이게 없으면 SQL 원문·컬럼명이 그대로 클라이언트 응답에 노출된다(정보 노출 + 원인 파악 불가).
     * 실제 원인은 서버 로그에만 남기고, 사용자에게는 "입력값이 올바르지 않다"는 일반 메시지만 준다.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("DB 제약조건 위반(Bean Validation이 못 걸러낸 입력값): {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다. 값을 확인한 뒤 다시 시도해주세요.");
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message));
    }
}
