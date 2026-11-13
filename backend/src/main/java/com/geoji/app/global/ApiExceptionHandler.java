package com.geoji.app.global;

import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiResponse<Void>> api(ApiException e) { return ResponseEntity.status(e.status()).body(ApiResponse.fail(e.code(), e.getMessage())); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> validation() { return ResponseEntity.badRequest().body(ApiResponse.fail("INVALID_REQUEST", "입력값을 확인해주세요.")); }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse<Void>> json() { return ResponseEntity.badRequest().body(ApiResponse.fail("INVALID_REQUEST", "요청 형식이 올바르지 않습니다.")); }
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    ResponseEntity<ApiResponse<Void>> conflict() { return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.fail("CONFLICT", "이미 사용 중인 계정 정보입니다.")); }
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiResponse<Void>> parameter() { return ResponseEntity.badRequest().body(ApiResponse.fail("INVALID_REQUEST", "요청 파라미터 형식이 올바르지 않습니다.")); }
}
