package dev.abex.gov.core;

/** Outcome of a player action: ok plus a message to show them, or a value for the caller. */
public record Result(boolean ok, String message, String value) {
    public static Result ok(String message) { return new Result(true, message, null); }
    public static Result ok(String message, String value) { return new Result(true, message, value); }
    public static Result no(String message) { return new Result(false, message, null); }
}
