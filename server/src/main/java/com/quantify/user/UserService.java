package com.quantify.user;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.quantify.auth.ScopeResolver;
import com.quantify.common.BizException;
import com.quantify.common.ErrorCode;
import com.quantify.common.PageResult;
import com.quantify.common.Role;
import com.quantify.log.AuditLogService;
import com.quantify.org.ClassMapper;
import com.quantify.org.DeptMapper;
import com.quantify.org.GradeMapper;
import com.quantify.org.SysClass;
import com.quantify.org.SysDept;
import com.quantify.org.SysGrade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 账号管理。
 *
 * <p>核心约束：<b>服务端不保存明文密码</b>。创建和重置时明文只在响应里出现一次，
 * 数据库里存的是 BCrypt 密文。服务器一旦被攻破，拿到的也只是密文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private static final String PWD_CHARS = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private final UserMapper userMapper;
    private final DeptMapper deptMapper;
    private final ClassMapper classMapper;
    private final GradeMapper gradeMapper;
    private final PasswordEncoder passwordEncoder;
    private final ScopeResolver scopeResolver;
    private final AuditLogService auditLogService;
    private final com.quantify.auth.LoginAttemptService loginAttemptService;

    public PageResult<UserVO> page(long page, long size, String keyword, String role,
                                   Integer scopeType, Integer status) {
        var qw = Wrappers.<SysUser>lambdaQuery();
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(SysUser::getUsername, keyword).or().like(SysUser::getRealName, keyword));
        }
        if (role != null && !role.isBlank()) {
            qw.eq(SysUser::getRole, role);
        }
        if (scopeType != null) {
            qw.eq(SysUser::getScopeType, scopeType);
        }
        if (status != null) {
            qw.eq(SysUser::getStatus, status);
        }
        qw.orderByAsc(SysUser::getId);

        var p = userMapper.selectPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size), qw);
        List<UserVO> list = p.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(p.getTotal(), page, size, list);
    }

    @Transactional(rollbackFor = Exception.class)
    public CreateUserVO create(UserDTO dto) {
        validateRoleAndScope(dto.getRole(), dto.getScopeType(), dto.getScopeId());

        Long dup = userMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, dto.getUsername()));
        if (dup != null && dup > 0) {
            throw BizException.of("用户名已存在：" + dto.getUsername());
        }

        // 管理员没填就随机生成；明文只活在这个方法的局部变量里
        boolean generated = dto.getPassword() == null || dto.getPassword().isBlank();
        String rawPassword = generated ? randomPassword() : dto.getPassword();
        if (rawPassword.length() < 6) {
            throw new BizException(ErrorCode.BAD_REQUEST, "密码长度不能少于 6 位");
        }

        SysUser u = new SysUser();
        u.setUsername(dto.getUsername());
        u.setPassword(passwordEncoder.encode(rawPassword));
        u.setRealName(dto.getRealName());
        u.setRole(dto.getRole());
        u.setDeptId(dto.getDeptId());
        u.setScopeType(dto.getScopeType());
        u.setScopeId(dto.getScopeType() == 1 ? 0L : dto.getScopeId());
        u.setStudentId(dto.getStudentId());
        u.setRemark(dto.getRemark());
        u.setStatus(1);
        u.setPwdChanged(0);              // 首登强制改密
        u.setCreatedBy(com.quantify.auth.ScopeContext.getUserId());
        userMapper.insert(u);

        // 日志里绝不写密码
        Map<String, Object> after = new HashMap<>();
        after.put("username", u.getUsername());
        after.put("realName", u.getRealName());
        after.put("role", u.getRole());
        after.put("scopeType", u.getScopeType());
        after.put("scopeId", u.getScopeId());
        auditLogService.record(com.quantify.auth.ScopeContext.getUserId(),
                com.quantify.auth.ScopeContext.getDeptId(), 0L,
                "USER", u.getId(), "CREATE", null, after);

        scopeResolver.evict(u.getId());
        return new CreateUserVO(u.getId(), u.getUsername(), u.getRealName(),
                u.getRole(), scopeName(u.getScopeType()), rawPassword, generated);
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, UserDTO dto) {
        SysUser exist = userMapper.selectById(id);
        if (exist == null) {
            throw BizException.notFound("账号不存在");
        }
        if (dto.getRole() != null) {
            validateRoleAndScope(dto.getRole(), dto.getScopeType(), dto.getScopeId());
        }

        SysUser upd = new SysUser();
        upd.setId(id);
        upd.setRealName(dto.getRealName());
        upd.setRole(dto.getRole());
        upd.setDeptId(dto.getDeptId());
        upd.setScopeType(dto.getScopeType());
        if (dto.getScopeType() != null) {
            upd.setScopeId(dto.getScopeType() == 1 ? 0L : dto.getScopeId());
        }
        upd.setRemark(dto.getRemark());
        // username 不在更新列表里：改了历史记录就对不上了
        userMapper.updateById(upd);

        // 范围变了，缓存必须失效，否则新范围不生效
        scopeResolver.evict(id);
        auditLogService.record(com.quantify.auth.ScopeContext.getUserId(),
                com.quantify.auth.ScopeContext.getDeptId(), 0L,
                "USER", id, "UPDATE", null, null);
    }

    /** 重置密码：生成新密码，强制改密，并把人踢下线 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> resetPassword(Long id, String newPassword) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw BizException.notFound("账号不存在");
        }
        boolean generated = newPassword == null || newPassword.isBlank();
        String raw = generated ? randomPassword() : newPassword;
        if (raw.length() < 6) {
            throw new BizException(ErrorCode.BAD_REQUEST, "密码长度不能少于 6 位");
        }

        SysUser upd = new SysUser();
        upd.setId(id);
        upd.setPassword(passwordEncoder.encode(raw));
        upd.setPwdChanged(0);
        userMapper.updateById(upd);

        // 旧 token 立即失效
        cn.dev33.satoken.stp.StpUtil.logout(id);

        // 顺手解锁登录失败计数：
        // 否则会出现"管理员刚帮人重置了密码，对方却因为之前试错太多次还是登不上"，
        // 而且报的是"账号已锁定"，看起来像是重置没生效。
        loginAttemptService.unlock(user.getUsername());

        auditLogService.record(com.quantify.auth.ScopeContext.getUserId(),
                com.quantify.auth.ScopeContext.getDeptId(), 0L,
                "USER", id, "RESET_PASSWORD", null, null);

        Map<String, Object> r = new HashMap<>();
        r.put("userId", id);
        r.put("username", user.getUsername());
        r.put("newPassword", raw);
        r.put("passwordGenerated", generated);
        return r;
    }

    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "状态只能是 0 或 1");
        }
        // 不能把自己禁用，否则管理员会把自己锁在门外
        if (status == 0 && id.equals(com.quantify.auth.ScopeContext.getUserId())) {
            throw BizException.of("不能禁用当前登录的账号");
        }
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw BizException.notFound("账号不存在");
        }

        SysUser upd = new SysUser();
        upd.setId(id);
        upd.setStatus(status);
        userMapper.updateById(upd);

        if (status == 0) {
            cn.dev33.satoken.stp.StpUtil.logout(id);
        }
        scopeResolver.evict(id);

        auditLogService.record(com.quantify.auth.ScopeContext.getUserId(),
                com.quantify.auth.ScopeContext.getDeptId(), 0L,
                "USER", id, status == 1 ? "ENABLE" : "DISABLE", null, null);
    }

    // ------------------------------------------------------------------

    private void validateRoleAndScope(String role, Integer scopeType, Long scopeId) {
        if (role == null || !(Role.ADMIN.equals(role) || Role.EDITOR.equals(role) || Role.VIEWER.equals(role))) {
            throw new BizException(ErrorCode.BAD_REQUEST, "角色只能是 ADMIN / EDITOR / VIEWER");
        }
        if (scopeType == null || scopeType < 1 || scopeType > 3) {
            throw new BizException(ErrorCode.BAD_REQUEST, "数据范围只能是 1全院 / 2本年级 / 3本班");
        }
        if (scopeType == 2) {
            if (scopeId == null || gradeMapper.selectById(scopeId) == null) {
                throw BizException.of("指定的年级不存在");
            }
        } else if (scopeType == 3) {
            if (scopeId == null || classMapper.selectById(scopeId) == null) {
                throw BizException.of("指定的班级不存在");
            }
        }
    }

    private String randomPassword() {
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(PWD_CHARS.charAt(rnd.nextInt(PWD_CHARS.length())));
        }
        return sb.toString();
    }

    private UserVO toVO(SysUser u) {
        UserVO vo = new UserVO();
        vo.setId(u.getId());
        vo.setUsername(u.getUsername());
        vo.setRealName(u.getRealName());
        vo.setRole(u.getRole());
        vo.setRoleText(roleText(u.getRole()));
        vo.setDeptId(u.getDeptId());
        vo.setDeptName(deptName(u.getDeptId()));
        vo.setRemark(u.getRemark());
        vo.setScopeType(u.getScopeType());
        vo.setScopeId(u.getScopeId());
        vo.setScopeName(scopeName(u.getScopeType()));
        vo.setScopeDetail(scopeDetail(u));
        vo.setStatus(u.getStatus());
        vo.setPwdChanged(u.getPwdChanged() != null && u.getPwdChanged() == 1);
        vo.setLastLoginAt(u.getLastLoginAt());
        vo.setCreatedAt(u.getCreatedAt());
        return vo;
    }

    private String scopeDetail(SysUser u) {
        if (u.getScopeType() == null) {
            return "";
        }
        if (u.getScopeType() == 2 && u.getScopeId() != null) {
            SysGrade g = gradeMapper.selectById(u.getScopeId());
            return g == null ? "" : g.getGradeName();
        }
        if (u.getScopeType() == 3 && u.getScopeId() != null) {
            SysClass c = classMapper.selectById(u.getScopeId());
            return c == null ? "" : c.getName();
        }
        return "全院";
    }

    private String deptName(Long deptId) {
        if (deptId == null) {
            return null;
        }
        SysDept d = deptMapper.selectById(deptId);
        return d == null ? null : d.getDeptName();
    }

    static String roleText(String role) {
        if (role == null) {
            return "";
        }
        return switch (role) {
            case "ADMIN" -> "审核员";
            case "EDITOR" -> "录入员";
            case "VIEWER" -> "查看员";
            default -> role;
        };
    }

    static String scopeName(Integer t) {
        if (t == null) {
            return "";
        }
        return switch (t) {
            case 1 -> "全院";
            case 2 -> "本年级";
            case 3 -> "本班";
            default -> "";
        };
    }
}
