package com.example.ttsserver.error;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String type;
    private final String param;
    private final String code;

    public ApiException(HttpStatus status, String message, String type, String param, String code) {
        super(message);
        this.status = status;
        this.type = type;
        this.param = param;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getType() {
        return type;
    }

    public String getParam() {
        return param;
    }

    public String getCode() {
        return code;
    }
}
