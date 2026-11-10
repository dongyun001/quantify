package com.quantify.config;

import com.quantify.user.SysUser;
import com.quantify.user.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 首次启动时创建一个初始管理员。
 *
 * <p>密码要 BCrypt 加密，不能写死在 SQL 里——所以账号由代码建，不由建表脚本建。
 * <b>id 固定为 1</b>：seed_data.sql 里的 created_by/reviewed_by 都指向 1，
 * 靠这个约定把测试数据和账号对上。
 *
 * <p>只在账号表为空时执行，之后重启不会覆盖已有账号。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Value("${quantify.init-admin-username:admin}")
    private String adminUsername;

    @Value("${quantify.init-admin-password:123456}")
    private String adminPassword;

    @Override
    public void run(ApplicationArguments args) {
        Long count = userMapper.selectCount(null);
        if (count != null && count > 0) {
            log.info("账号表已有 {} 条记录，跳过初始化", count);
            return;
        }

        SysUser admin = new SysUser();
        admin.setId(1L);
        admin.setUsername(adminUsername);
        admin.setPassword(passwordEncoder.encode(adminPassword));
        admin.setRealName("系统管理员");
        admin.setRole("ADMIN");
        admin.setDeptId(null);
        admin.setScopeType(1);          // 全院
        admin.setScopeId(0L);
        admin.setRemark("初始管理员，登录后请立即修改密码");
        admin.setStatus(1);
        admin.setPwdChanged(0);         // 强制首次登录改密
        userMapper.insert(admin);

        log.info("""
                
                ============================================================
                  已创建初始管理员账号
                    用户名：{}
                    密码：{}
                  首次登录会强制修改密码
                ============================================================
                """, adminUsername, adminPassword);
    }
}
