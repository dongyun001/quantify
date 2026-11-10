package com.quantify.auth;

import lombok.Data;

import java.util.List;

/**
 * 当前请求的数据范围上下文。
 *
 * <p>请求进来时由 {@link ScopeInterceptor} 填好，业务代码直接取，
 * 请求结束必须清理——<b>Tomcat 复用线程，不清会串号</b>，
 * 上一个请求残留的范围会被下一个请求读到，表现为"看到别的班的数据"。
 */
public final class ScopeContext {

    @Data
    public static class Current {
        private Long userId;
        private String username;
        private String realName;
        private String role;
        private Long deptId;
        private Integer scopeType;
        private Long scopeId;
        /** 能看到的班级 id 列表：本班 1 个、本年级十几个、全院四十几个 */
        private List<Long> classIds;
    }

    private static final ThreadLocal<Current> HOLDER = new ThreadLocal<>();

    private ScopeContext() {
    }

    public static void set(Current c) {
        HOLDER.set(c);
    }

    public static Current get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    // ---- 便捷方法 ----

    public static Long getUserId() {
        Current c = HOLDER.get();
        return c == null ? null : c.getUserId();
    }

    public static String getRole() {
        Current c = HOLDER.get();
        return c == null ? null : c.getRole();
    }

    public static Long getDeptId() {
        Current c = HOLDER.get();
        return c == null ? null : c.getDeptId();
    }

    public static List<Long> getClassIds() {
        Current c = HOLDER.get();
        return c == null ? List.of() : c.getClassIds();
    }

    /** 这个班级在当前账号的可见范围内吗 */
    public static boolean canSee(Long classId) {
        return classId != null && getClassIds().contains(classId);
    }
}
