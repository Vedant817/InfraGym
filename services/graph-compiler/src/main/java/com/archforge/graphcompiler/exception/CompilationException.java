package com.archforge.graphcompiler.exception;

public class CompilationException extends RuntimeException {

    public CompilationException(String message) {
        super(message);
    }

    public CompilationException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Distinguishes "the graph is not compilable" from "something broke".
     *
     * <p>This distinction decides the HTTP status a user sees. A cycle is the caller's
     * fault and must be a 4xx; an internal failure is ours and must be a 5xx. The
     * previous code threw the same exception type for both, so every cycle surfaced
     * as a 500 and users were told to retry a request that could never succeed.
     */
    public static class UncompilableGraph extends CompilationException {
        public UncompilableGraph(String message) {
            super(message);
        }
    }
}
