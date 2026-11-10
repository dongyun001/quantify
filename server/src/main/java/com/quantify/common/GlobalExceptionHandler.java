package com.quantify.common;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理。
 *
 * <p>把各类异常统一转成 {@code {code, msg, data:null}}，并且**HTTP 状态码一律 200**。
 * 前端只要判断 body 里的 code，不用处理各种 HTTP 异常分支。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：预期内的，不打堆栈 */
    @ExceptionHandler(BizException.class)
    public R<Void> handleBiz(BizException e) {
        return R.fail(e.getErrorCode(), e.getMessage());
    }

    /** 未登录 / token 失效 */
    @ExceptionHandler(NotLoginException.class)
    public R<Void> handleNotLogin(NotLoginException e) {
        return R.fail(ErrorCode.UNAUTHORIZED);
    }

    /** 角色不够 */
    @ExceptionHandler({NotRoleException.class, NotPermissionException.class})
    public R<Void> handleNoPermission(Exception e) {
        return R.fail(ErrorCode.FORBIDDEN);
    }

    /**
     * 参数校验失败。
     * 只返回第一条错误的具体提示，前端直接展示即可——
     * "学号不能为空"比"参数校验失败"有用得多。
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public R<Void> handleValid(Exception e) {
        FieldError fe = null;
        if (e instanceof MethodArgumentNotValidException me) {
            fe = me.getBindingResult().getFieldError();
        } else if (e instanceof BindException be) {
            fe = be.getBindingResult().getFieldError();
        }
        String msg = fe != null ? fe.getDefaultMessage() : ErrorCode.BAD_REQUEST.getMsg();
        return R.fail(ErrorCode.BAD_REQUEST, msg);
    }

    /**
     * 路径变量 / 查询参数类型不对，例如把 /api/scores/None/submit 这种非法 id 传进来。
     * <b>这是客户端错误，不是服务器错误</b>，所以返回 501 而不是 500——
     * 否则日志里会堆满"系统繁忙"，真正的服务端 bug 反而被淹没。
     */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public R<Void> handleTypeMismatch(Exception e) {
        return R.fail(ErrorCode.BAD_REQUEST, "参数格式不正确");
    }

    /** 兜底：真正没预料到的异常，打完整堆栈 */
    @ExceptionHandler(Exception.class)
    public R<Void> handleOther(Exception e, HttpServletRequest req) {
        log.error("未处理的异常 {} {}", req.getMethod(), req.getRequestURI(), e);
        return R.fail(ErrorCode.SERVER_ERROR);
    }
}
