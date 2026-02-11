package com.example.ttsserver.error;

import com.example.ttsserver.api.common.OpenAiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import jakarta.validation.ConstraintViolationException;

import java.net.SocketTimeoutException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<OpenAiErrorResponse> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(error(ex.getMessage(), ex.getType(), ex.getParam(), ex.getCode()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<OpenAiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        var fieldError = ex.getBindingResult().getFieldError();
        String message = fieldError == null ? "Validation failed" : fieldError.getDefaultMessage();
        String param = fieldError == null ? null : fieldError.getField();
        return ResponseEntity.badRequest().body(error(message, "invalid_request_error", param, "validation_error"));
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class})
    ResponseEntity<OpenAiErrorResponse> handleMissing(Exception ex) {
        return ResponseEntity.badRequest().body(error(ex.getMessage(), "invalid_request_error", null, "missing_parameter"));
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class, ConstraintViolationException.class})
    ResponseEntity<OpenAiErrorResponse> handleBadRequest(Exception ex) {
        return ResponseEntity.badRequest().body(error(ex.getMessage(), "invalid_request_error", null, "validation_error"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<OpenAiErrorResponse> handleMaxSize(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(error("Uploaded file is too large", "invalid_request_error", "file", "file_too_large"));
    }

    @ExceptionHandler(SocketTimeoutException.class)
    ResponseEntity<OpenAiErrorResponse> handleTimeout(SocketTimeoutException ex) {
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(error("Upstream timeout", "server_error", null, "upstream_timeout"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<OpenAiErrorResponse> handleFallback(Exception ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(error("Upstream error while calling " + request.getRequestURI(), "server_error", null, "upstream_error"));
    }

    private OpenAiErrorResponse error(String message, String type, String param, String code) {
        return new OpenAiErrorResponse(new OpenAiErrorResponse.ErrorBody(message, type, param, code));
    }
}
