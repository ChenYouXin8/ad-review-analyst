package io.github.chenyouxin8.adreview.common;

import java.time.LocalDateTime;

/**
 * 统一 API 响应格式
 */
public record ApiResponse<T>(
        int code,
        String message,
        T data,
        LocalDateTime time
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "操作成功", data, LocalDateTime.now());
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(0, "操作成功", null, LocalDateTime.now());
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null, LocalDateTime.now());
    }

    public static <T> ApiResponse<T> badRequest(String message) {
        return error(40000, message);
    }

    public static <T> ApiResponse<T> serverError(String message) {
        return error(50000, message);
    }
}
