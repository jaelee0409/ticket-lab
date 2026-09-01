package com.ticketlab.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "COMMON_001", "요청 값이 올바르지 않습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_002", "서버 오류가 발생했습니다."),
    EMAIL_ALREADY_USED(HttpStatus.CONFLICT, "AUTH_001", "이미 사용 중인 이메일입니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH_002", "이메일 또는 비밀번호가 올바르지 않습니다."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "AUTH_003", "인증이 필요합니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_004", "리프레시 토큰이 유효하지 않습니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_001", "사용자를 찾을 수 없습니다."),
    SEAT_NOT_FOUND(HttpStatus.NOT_FOUND, "SEAT_001", "좌석을 찾을 수 없습니다."),
    SEAT_NOT_AVAILABLE(HttpStatus.CONFLICT, "SEAT_002", "이미 선점되었거나 판매된 좌석입니다."),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "RESV_001", "예약을 찾을 수 없습니다."),
    RESERVATION_FORBIDDEN(HttpStatus.FORBIDDEN, "RESV_002", "본인의 예약이 아닙니다."),
    RESERVATION_NOT_PENDING(HttpStatus.CONFLICT, "RESV_003", "확정할 수 있는 상태가 아닙니다."),
    RESERVATION_EXPIRED(HttpStatus.CONFLICT, "RESV_004", "선점 시간이 만료되었습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}