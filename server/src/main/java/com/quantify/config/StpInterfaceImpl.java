package com.quantify.config;

import cn.dev33.satoken.stp.StpInterface;
import com.quantify.user.SysUser;
import com.quantify.user.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把数据库里的角色告诉 Sa-Token，@SaCheckRole 才能生效。
 *
 * <p>权限只看 role，不看部门——部门是账号上的可选标签，不参与鉴权判定。
 * 这样以后组织架构调整时不用动权限代码。
 */
@Component
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    private final UserMapper userMapper;

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        SysUser user = userMapper.selectById(Long.valueOf(loginId.toString()));
        if (user == null || user.getRole() == null) {
            return List.of();
        }
        return List.of(user.getRole());
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        // 本项目用角色即可，不做细粒度权限码
        return List.of();
    }
}
