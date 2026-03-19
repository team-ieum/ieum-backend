package com.ieum.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SuccessCode {

    OK(HttpStatus.OK, "OK"),
    CREATED(HttpStatus.CREATED, "CREATED"),
    ACCEPTED(HttpStatus.ACCEPTED, "ACCEPTED"),
    NO_CONTENT(HttpStatus.NO_CONTENT, "NO_CONTENT");

    private final HttpStatus status;
    private final String message;
}
