package com.ripsim.sift.exception;

/** A non-upload API error (e.g. unknown batch) with an HTTP status and a stable machine-readable code. */
public class ApiException extends RuntimeException {
    private final int httpStatus;
    private final String code;

    public ApiException(int httpStatus, String code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }
}
