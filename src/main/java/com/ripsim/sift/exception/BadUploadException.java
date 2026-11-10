package com.ripsim.sift.exception;

/** Client-side upload problem. Carries a stable machine-readable code and the HTTP status to answer with. */
public class BadUploadException extends RuntimeException {
    private final int httpStatus;
    private final String code;

    public BadUploadException(int httpStatus, String code, String message) {
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
