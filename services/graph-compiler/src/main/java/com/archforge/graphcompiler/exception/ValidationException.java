package com.archforge.graphcompiler.exception;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

@Getter
public class ValidationException extends RuntimeException {

    private final List<String> errors;

    public ValidationException(List<String> errors) {
        super("Topology validation failed with " + errors.size() + " error(s)");
        this.errors = new ArrayList<>(errors);
    }

    public ValidationException(String message) {
        super(message);
        this.errors = new ArrayList<>();
        this.errors.add(message);
    }

    public void addError(String error) {
        this.errors.add(error);
    }
}
