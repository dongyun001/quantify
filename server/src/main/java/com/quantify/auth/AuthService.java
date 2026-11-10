package com.quantify.auth;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.quantify.common.BizException;
import com.quantify.common.ErrorCode;
import com.quantify.log.AuditLogService;
import com.quantify.org.ClassMapper;
import com.quantify.org.DeptMapper;
import com.quantify.org.GradeMapper;
import com.quantify.org.SysClass;
import com.quantify.org.SysDept;
import com.quantify.org.SysGrade;
import com.quantify.setting.SysConfig;
import com.quantify.setting.SysConfigMapper;
import com.quantify.student.Student;
import com.quantify.student.StudentMapper;
import com.quantify.user.SysUser;
import com.quantify.user.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final ClassMapper classMapper;
    private final GradeMapper gradeMapper;
    private final DeptMapper deptMapper;
    private final StudentMapper studentMapper;
    private final SysConfigMapper configMapper;
    private final PasswordEncoder passwordEncoder;
    private final ScopeResolver scopeResolver;
    private final AuditLogService auditLogService;
    private final LoginAttemptService loginAttemptService;

    public LoginVO login(LoginDTO dto, String ip) {
        // ① 先看是否被锁。
        //    必须放在校验密码【之前】——被锁期间还去跑 BCrypt 比对，等于白送攻击者算力，
        //    而且 BCrypt 是故意设计成很慢的，几十个并发就能把 CPU 吃满。
        //    这里对"账号不存在"的情况同样判断，否则攻击者能靠"是否被锁"反推真实用户名。
        long lockRemain = loginAttemptService.lockedRemainSeconds(dto.getUsername());
        if (lockRemain > 0) {
            auditLogService.recordLoginFail(dto.getUsername(), ip);
            long minutes = (lockRemain + 59) / 60;   // 向上取整，避免显示"请 0 分钟后重试"
            throw new BizException(ErrorCode.BIZ_ERROR,
                    "登录失败次数过多，账号已被锁定，请 " + minutes + " 分钟后再试");
        }

        SysUser user = userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, dto.getUsername()));

        // ② 用户不存在和密码错误返回同一句话，避免被用来枚举账号
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            loginAttemptService.recordFailure(dto.getUsername());
            auditLogService.recordLoginFail(dto.getUsername(), ip);
            throw new BizException(ErrorCode.BIZ_ERROR, "用户名或密码错误");
        }
        // 账号被禁用不算"密码错误"，不参与失败计数——否则管理员禁用一个人之后，
        // 那个人再试几次就把账号也锁了，管理员都不知道为什么解不开。
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(ErrorCode.BIZ_ERROR, "账号已禁用，请联系管理员");
        }

        // ③ 登录成功，清零计数
        loginAttemptService.recordSuccess(dto.getUsername());

        StpUtil.login(user.getId());
        userMapper.update(null, Wrappers.<SysUser>lambdaUpdate()
                .eq(SysUser::getId, user.getId())
                .set(SysUser::getLastLoginAt, LocalDateTime.now()));

        auditLogService.recordLogin(user.getId(), user.getDeptId(), ip);

        LoginVO vo = new LoginVO();
        vo.setToken(StpUtil.getTokenValue());
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setRole(user.getRole());
        vo.setDeptId(user.getDeptId());
        vo.setDeptName(queryDeptName(user.getDeptId()));
        vo.setPwdChanged(user.getPwdChanged() != null && user.getPwdChanged() == 1);
        return vo;
    }

    public void logout(Long userId, String ip) {
        auditLogService.recordLogout(userId, ScopeContext.getDeptId(), ip);
        StpUtil.logout(userId);
    }

    public CurrentUserVO currentUser() {
        ScopeContext.Current cur = ScopeContext.get();
        if (cur == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        SysUser user = userMapper.selectById(cur.getUserId());
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }

        CurrentUserVO vo = new CurrentUserVO();
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setRole(user.getRole());
        vo.setDeptId(user.getDeptId());
        vo.setDeptName(queryDeptName(user.getDeptId()));
        vo.setRemark(user.getRemark());
        vo.setScopeType(user.getScopeType());
        vo.setScopeId(user.getScopeId());
        vo.setScopeName(scopeName(user.getScopeType()));
        vo.setStudentId(user.getStudentId());
        vo.setPwdChanged(user.getPwdChanged() != null && user.getPwdChanged() == 1);

        List<Long> classIds = cur.getClassIds();
        vo.setClassCount(classIds == null ? 0 : classIds.size());

        // 范围收窄到本班/本年级时，把班级和年级名字带出来给前端显示
        if (user.getScopeType() != null && user.getScopeType() == 3 && user.getScopeId() != null) {
            SysClass c = classMapper.selectById(user.getScopeId());
            if (c != null) {
                vo.setClassId(c.getId());
                vo.setClassName(c.getName());
                vo.setGradeId(c.getGradeId());
                SysGrade g = gradeMapper.selectById(c.getGradeId());
                if (g != null) {
                    vo.setGradeName(g.getGradeName());
                }
            }
        } else if (user.getScopeType() != null && user.getScopeType() == 2 && user.getScopeId() != null) {
            SysGrade g = gradeMapper.selectById(user.getScopeId());
            if (g != null) {
                vo.setGradeId(g.getId());
                vo.setGradeName(g.getGradeName());
            }
        }

        // 账号绑定了学生的话，把学生信息也带上（班委账号用）
        if (user.getStudentId() != null) {
            Student stu = studentMapper.selectById(user.getStudentId());
            if (stu != null) {
                vo.setStudentId(stu.getId());
                vo.setClassId(stu.getClassId());
                SysClass c = classMapper.selectById(stu.getClassId());
                if (c != null) {
                    vo.setClassName(c.getName());
                }
            }
        }
        return vo;
    }

    /**
     * 修改密码。
     *
     * <p>改完强制下线，需要用新密码重新登录——避免旧 token 继续可用。
     */
    public void changePassword(Long userId, String oldPwd, String newPwd, String ip) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (!passwordEncoder.matches(oldPwd, user.getPassword())) {
            throw new BizException(ErrorCode.BIZ_ERROR, "原密码错误");
        }
        if (newPwd == null || newPwd.length() < 6) {
            throw new BizException(ErrorCode.BAD_REQUEST, "新密码长度不能少于 6 位");
        }
        if (passwordEncoder.matches(newPwd, user.getPassword())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "新密码不能与原密码相同");
        }

        userMapper.update(null, Wrappers.<SysUser>lambdaUpdate()
                .eq(SysUser::getId, userId)
                .set(SysUser::getPassword, passwordEncoder.encode(newPwd))
                .set(SysUser::getPwdChanged, 1));

        auditLogService.recordPasswordChange(userId, user.getDeptId(), ip);
        StpUtil.logout(userId);
    }

    private String queryDeptName(Long deptId) {
        if (deptId == null) {
            return null;
        }
        SysDept d = deptMapper.selectById(deptId);
        return d == null ? null : d.getDeptName();
    }

    private String scopeName(Integer scopeType) {
        if (scopeType == null) {
            return "";
        }
        return switch (scopeType) {
            case 1 -> "全院";
            case 2 -> "本年级";
            case 3 -> "本班";
            default -> "未知";
        };
    }

    /** 取配置项，取不到返回默认值 */
    public String config(String key, String defaultValue) {
        SysConfig c = configMapper.selectOne(Wrappers.<SysConfig>lambdaQuery()
                .eq(SysConfig::getClassId, 0L)
                .eq(SysConfig::getConfigKey, key));
        return c == null || c.getConfigValue() == null || c.getConfigValue().isBlank()
                ? defaultValue : c.getConfigValue();
    }
}
