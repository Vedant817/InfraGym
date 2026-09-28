package com.archforge.graphcompiler.validation;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ValidationError {
    private String field;
    private String code;
    private String message;
    private Severity severity;

    public enum Severity {
        ERROR,
        WARNING
    }
}
