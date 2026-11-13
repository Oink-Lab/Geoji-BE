package com.geoji.app.global;

public record ApiResponse<T>(boolean success, T data, ApiError error) {
    public static <T> ApiResponse<T> ok(T data) { return new ApiResponse<>(true, data, null); }
    public static ApiResponse<Void> fail(String code, String message) { return new ApiResponse<>(false, null, new ApiError(code, message)); }
    public record ApiError(String code, String message) {}
}
