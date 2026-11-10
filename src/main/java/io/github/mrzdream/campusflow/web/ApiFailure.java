package io.github.mrzdream.campusflow.web;

import org.springframework.http.HttpStatus;

public class ApiFailure extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiFailure(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    public static ApiFailure missing(String code, String detail) {
        return new ApiFailure(HttpStatus.NOT_FOUND, code, detail);
    }

    public static ApiFailure conflict(String code, String detail) {
        return new ApiFailure(HttpStatus.CONFLICT, code, detail);
    }

    public static ApiFailure invalid(String code, String detail) {
        return new ApiFailure(HttpStatus.BAD_REQUEST, code, detail);
    }
}
