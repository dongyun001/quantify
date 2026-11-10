package com.quantify.auth;

import cn.dev33.satoken.stp.StpUtil;
import com.quantify.common.BizException;
import com.quantify.common.ErrorCode;
import com.quantify.user.SysUser;
import com.quantify.user.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 填充当前请求的数据范围上下文。
 *
 * <p>注册顺序在 Sa-Token 的登录校验之后，所以走到这里时一定是已登录的。
 *
 * <p>清理放在 {@code afterCompletion}，异常路径在 catch 里也清一遍——
 * Tomcat 复用线程，ThreadLocal 不清会导致 A 请求残留的范围被 B 请求读到，
 * 表现为"看到别的班的数据"，而且不崩不报错。
 */
@Component
@RequiredArgsConstructor
public class ScopeInterceptor implements HandlerInterceptor {

    private final UserMapper userMapper;
    private final ScopeResolver scopeResolver;

    /** 初始密码未改时仍然放行的接口 */
    private static final String[] PWD_CHANGE_ALLOW = {
            "/api/auth/password", "/api/auth/logout", "/api/auth/me"
    };

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        try {
            Object loginId = StpUtil.getLoginIdDefaultNull();
            if (loginId == null) {
                // 未登录的请求由 Sa-Token 拦截器负责拒绝，这里直接放行
                return true;
            }

            Long userId = Long.valueOf(loginId.toString());
            SysUser user = userMapper.selectById(userId);
            if (user == null || user.getStatus() == null || user.getStatus() != 1) {
                StpUtil.logout();
                throw new BizException(ErrorCode.UNAUTHORIZED, "账号不存在或已被禁用");
            }

            ScopeContext.Current cur = new ScopeContext.Current();
            cur.setUserId(user.getId());
            cur.setUsername(user.getUsername());
            cur.setRealName(user.getRealName());
            cur.setRole(user.getRole());
            cur.setDeptId(user.getDeptId());
            cur.setScopeType(user.getScopeType());
            cur.setScopeId(user.getScopeId());
            cur.setClassIds(scopeResolver.resolveClassIds(user));
            ScopeContext.set(cur);

            // 还在用初始密码：除改密相关接口外全部拦下
            if (user.getPwdChanged() == null || user.getPwdChanged() == 0) {
                String uri = request.getRequestURI();
                for (String allow : PWD_CHANGE_ALLOW) {
                    if (uri.startsWith(allow)) {
                        return true;
                    }
                }
                throw new BizException(ErrorCode.PWD_NOT_CHANGED);
            }

            return true;
        } catch (RuntimeException e) {
            // 异常路径也必须清，否则线程被复用时会串号
            ScopeContext.clear();
            throw e;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        ScopeContext.clear();
    }
}
