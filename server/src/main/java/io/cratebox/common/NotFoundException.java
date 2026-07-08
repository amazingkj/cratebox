package io.cratebox.common;

/** 대상 없음 → HTTP 404 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
