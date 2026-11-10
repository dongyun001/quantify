package com.quantify.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import com.quantify.auth.ScopeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置：拦截器注册 + CORS。
 *
 * <p>拦截器顺序很重要——先做登录校验，再填充数据范围，
 * 这样 ScopeInterceptor 里可以放心地假设"一定是已登录的"。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final ScopeInterceptor scopeInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // ① 登录校验 + @SaCheckRole 注解鉴权
        registry.addInterceptor(new SaInterceptor(handle ->
                        // 所有 /api/** 都要登录，下面的白名单除外
                        cn.dev33.satoken.router.SaRouter.match("/api/**")
                                .notMatch("/api/auth/login")
                                .check(r -> StpUtil.checkLogin())))
                .addPathPatterns("/api/**")
                .order(1);

        // ② 填充当前账号的数据范围（依赖上一步已登录）
        registry.addInterceptor(scopeInterceptor)
                .addPathPatterns("/api/**")
                .order(2);
    }

    /**
     * CORS 兜底。
     * 开发期前端 5173 通过 Vite 代理访问，其实用不到这个；
     * 但用 Postman 或换端口调试时会需要。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Content-Disposition")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
