package com.quantify;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 量化积分管理系统 —— 后端入口。
 *
 * <p>权限模型：角色（能做什么）× 数据范围（能看多大）
 * <ul>
 *   <li>一级 ADMIN  —— 全能：录入、审核、查看、导出、开户</li>
 *   <li>二级 EDITOR —— 录入、提交申请、查看；只能改自己提交的</li>
 *   <li>三级 VIEWER —— 只读</li>
 * </ul>
 *
 * <p>Mapper 直接放在各模块包下（如 {@code com.quantify.user.UserMapper}），
 * 所以按 <b>注解</b> 扫描而不是按包名通配——通配符会把业务接口也扫进去。
 */
@SpringBootApplication
@EnableScheduling   // LoginAttemptService 的定期清理需要它
@EnableAsync        // 申诉的钉钉通知在事务提交后异步发送
@MapperScan(basePackages = "com.quantify", annotationClass = Mapper.class)
public class QuantifyApplication {

    public static void main(String[] args) {
        SpringApplication.run(QuantifyApplication.class, args);
    }

    /**
     * 申诉通知专用线程池。
     *
     * <p><b>必须有界</b>：webhook 长时间不可用时，无界队列会把任务一直堆到 OOM。
     * 队列满后用 CallerRunsPolicy 兜底——宁可让请求线程慢一下，也不要静默丢通知。
     */
    @Bean("appealNotifyExecutor")
    public ThreadPoolTaskExecutor appealNotifyExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(1);
        ex.setMaxPoolSize(2);
        ex.setQueueCapacity(100);
        ex.setThreadNamePrefix("appeal-notify-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        ex.initialize();
        return ex;
    }
}
