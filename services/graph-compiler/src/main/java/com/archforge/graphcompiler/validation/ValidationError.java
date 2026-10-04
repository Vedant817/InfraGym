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

    /**
     * Finding severity.
     *
     * <p>{@code INFO} was added because not every finding deserves to be surfaced at
     * warning level. The edge validator, for example, previously emitted one warning
     * per edge for an unrecognised <em>rendering</em> hint — a purely cosmetic value —
     * producing up to 2 000 warnings on a max-size topology. Separating "worth a
     * designer's attention" from "worth mentioning" is what keeps a warning list
     * readable.
     */
    public enum Severity {
        /** Blocks compilation. */
        ERROR,
        /** Does not block, but indicates a likely design mistake. */
        WARNING,
        /** Cosmetic or purely informational. */
        INFO
    }
}
