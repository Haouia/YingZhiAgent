package com.campagent.common;
import org.springframework.http.HttpStatus;
public class ApiException extends RuntimeException {
    public final HttpStatus status;
    public final int retryAfter;
    public ApiException(HttpStatus status, String message) { this(status,message,12); }
    public ApiException(HttpStatus status, String message, int retryAfter) { super(message); this.status=status; this.retryAfter=Math.max(1,retryAfter); }
    public static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST,message); }
    public static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"资源不存在或不可访问"); }
}

