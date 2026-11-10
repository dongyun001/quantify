package com.quantify.common;

import lombok.Getter;

/**
 * 业务异常。
 *
 * <p>业务代码里凡是"操作不被允许"的情况都抛它，由 {@link GlobalExceptionHandler}
 * 统一转成 {@code {code, msg, data:null}}，不写堆栈日志（这不是 bug）。
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMsg());
        this.errorCode = errorCode;
    }

    /** 用自定义提示覆盖默认文案，比如"只能修改自己提交的记录" */
    public BizException(ErrorCode errorCode, String msg) {
        super(msg);
        this.errorCode = errorCode;
    }

    public static BizException of(String msg) {
        return new BizException(ErrorCode.BIZ_ERROR, msg);
    }

    public static BizException notFound(String msg) {
        return new BizException(ErrorCode.NOT_FOUND, msg);
    }
}
