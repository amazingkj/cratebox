package io.cratebox.common;

/** 도메인 규칙 위반 → HTTP 400 */
public class DomainException extends RuntimeException {
    public DomainException(String message) {
        super(message);
    }
}
