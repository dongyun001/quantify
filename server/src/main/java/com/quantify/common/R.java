package com.quantify.common;

import lombok.Data;

/**
 * 统一响应体。
 *
 * <pre>
 * { "code": 0, "msg": "ok", "data": {...} }
 * </pre>
 */
@Data
public class R<T> {

    private int code;
    private String msg;
    private T data;

    private R(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> R<T> ok() {
        return new R<>(ErrorCode.OK.getCode(), ErrorCode.OK.getMsg(), null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(ErrorCode.OK.getCode(), ErrorCode.OK.getMsg(), data);
    }

    public static <T> R<T> fail(ErrorCode ec) {
        return new R<>(ec.getCode(), ec.getMsg(), null);
    }

    public static <T> R<T> fail(ErrorCode ec, String msg) {
        return new R<>(ec.getCode(), msg, null);
    }
}
