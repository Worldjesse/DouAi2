package com.ss.android.ugc.aweme.base.api.exceptions.server;

/** Exact 40.6.0 hint getter names/signatures; values intentionally contain private fixture text. */
public class ApiServerException extends Exception {
    public final int errorCode;
    private final String prompt, error;
    private final boolean throwOnRead;

    public ApiServerException(int code, String prompt, String error) { this(code, prompt, error, false); }
    public ApiServerException(int code, String prompt, String error, boolean throwOnRead) {
        super("private-throwable-message");
        errorCode = code; this.prompt = prompt; this.error = error; this.throwOnRead = throwOnRead;
    }
    public final String LIZLLL() {
        if (throwOnRead) throw new IllegalStateException("private-getter-failure");
        return prompt == null ? "" : prompt;
    }
    public final String LIZJ() { return error == null ? "" : error; }
    public OptionalDiagnosticType optionalDiagnosticType() { return null; }
    public static final class OptionalDiagnosticType { }
    @Override public String getMessage() { throw new AssertionError("Diagnostics must never call Throwable.getMessage()"); }
}
