package com.archforge.graphcompiler.validation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public class ValidationError {
    private final String field;
    private final String code;
    private final String message;
    private final Severity severity;

    public enum Severity {
        ERROR,
        WARNING
    }
}
