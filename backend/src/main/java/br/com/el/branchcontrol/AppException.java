package br.com.el.branchcontrol;

public class AppException extends RuntimeException {
    private final int status;
    public AppException(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
    public static AppException bad(String message) { return new AppException(400, message); }
    public static AppException conflict(String message) { return new AppException(409, message); }
}
