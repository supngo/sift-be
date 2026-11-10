package com.ripsim.sift.exception;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns an upload problem into a JSON error with a stable "code" field, e.g. {"code":"TRUNCATED_FRAME", ...}. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(BadUploadException.class)
    ProblemDetail handle(BadUploadException e) {
        return problem(e.httpStatus(), e.code(), e.getMessage());
    }

    @ExceptionHandler(ApiException.class)
    ProblemDetail handle(ApiException e) {
        return problem(e.httpStatus(), e.code(), e.getMessage());
    }

    private static ProblemDetail problem(int status, String code, String message) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), message);
        pd.setTitle(code);
        pd.setProperty("code", code);
        return pd;
    }
}
