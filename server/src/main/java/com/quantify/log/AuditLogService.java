package com.quantify.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 操作日志。
 *
 * <p>班级评分类系统最容易起争议，谁在什么时候把什么从多少改成了多少必须留痕。
 * 这里的原则是：<b>日志写失败绝不能影响主流程</b>——评分类操作因为日志报错而回滚，
 * 对用户来说是莫名其妙的。所以所有写入都包在 try/catch 里。
 *
 * <p>另外：<b>永远不要把密码写进日志</b>。日志常被导出、转发、长期留存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    /** 通用记录 */
    public void record(Long operatorId, Long deptId, Long classId, String targetType,
                       Long targetId, String action, Object before, Object after) {
        try {
            AuditLog entry = new AuditLog();
            entry.setOperatorId(operatorId);
            entry.setDeptId(deptId);
            entry.setClassId(classId == null ? 0L : classId);
            entry.setTargetType(targetType);
            entry.setTargetId(targetId == null ? 0L : targetId);
            entry.setAction(action);
            entry.setBeforeJson(toJson(before));
            entry.setAfterJson(toJson(after));
            entry.setCreatedAt(java.time.LocalDateTime.now());
            auditLogMapper.insert(entry);
        } catch (Exception e) {
            // 日志失败不能拖垮业务
            log.warn("写操作日志失败 action={} targetId={}", action, targetId, e);
        }
    }

    public void record(Long operatorId, Long deptId, Long classId, String targetType,
                       Long targetId, String action) {
        record(operatorId, deptId, classId, targetType, targetId, action, null, null);
    }

    // ---- 语义化的快捷方法，避免各处手拼 action 字符串写错 ----

    public void recordLogin(Long userId, Long deptId, String ip) {
        recordWithIp(userId, deptId, "USER", userId, "LOGIN", ip);
    }

    public void recordLogout(Long userId, Long deptId, String ip) {
        recordWithIp(userId, deptId, "USER", userId, "LOGOUT", ip);
    }

    public void recordLoginFail(String username, String ip) {
        try {
            AuditLog entry = new AuditLog();
            entry.setOperatorId(0L);
            entry.setClassId(0L);
            entry.setTargetType("USER");
            entry.setTargetId(0L);
            entry.setAction("LOGIN_FAIL");
            entry.setAfterJson("{\"username\":\"" + username + "\"}");
            entry.setOperatorIp(ip);
            entry.setCreatedAt(java.time.LocalDateTime.now());
            auditLogMapper.insert(entry);
        } catch (Exception e) {
            log.warn("记录登录失败日志出错", e);
        }
    }

    public void recordPasswordChange(Long userId, Long deptId, String ip) {
        // 注意：只记"改了密码"，不记密码内容
        recordWithIp(userId, deptId, "USER", userId, "CHANGE_PASSWORD", ip);
    }

    private void recordWithIp(Long operatorId, Long deptId, String targetType,
                              Long targetId, String action, String ip) {
        try {
            AuditLog entry = new AuditLog();
            entry.setOperatorId(operatorId);
            entry.setDeptId(deptId);
            entry.setClassId(0L);
            entry.setTargetType(targetType);
            entry.setTargetId(targetId);
            entry.setAction(action);
            entry.setOperatorIp(ip);
            entry.setCreatedAt(java.time.LocalDateTime.now());
            auditLogMapper.insert(entry);
        } catch (Exception e) {
            log.warn("写操作日志失败 action={}", action, e);
        }
    }

    private String toJson(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }
}
