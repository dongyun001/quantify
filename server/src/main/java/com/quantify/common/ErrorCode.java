package com.quantify.common;

import lombok.Getter;

/**
 * 全局错误码。
 *
 * <p>HTTP 状态码一律返回 200，错误信息放在响应体的 code 里。
 * 这样前端 axios 只需在响应拦截器里判断 code，不用处理各种 HTTP 异常分支。
 */
@Getter
public enum ErrorCode {

    OK(0, "ok"),

    /** token 失效，前端应清 token 并跳登录页 */
    UNAUTHORIZED(401, "登录已过期，请重新登录"),
    /** 角色或数据范围不够 */
    FORBIDDEN(403, "无权限操作"),
    /** 初始密码未改，除改密外一律拒绝 */
    PWD_NOT_CHANGED(4031, "请先修改初始密码"),
    NOT_FOUND(404, "数据不存在"),

    /** 参数校验失败 */
    BAD_REQUEST(501, "参数不正确"),
    /** 业务规则不允许 */
    BIZ_ERROR(502, "操作不被允许"),

    SERVER_ERROR(500, "系统繁忙，请稍后重试");

    private final int code;
    private final String msg;

    ErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
