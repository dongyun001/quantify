package com.quantify.common;

/**
 * 角色常量。权限只看角色，不看部门。
 */
public final class Role {

    /** 一级：全能 —— 录入 + 审核 + 查看 + 导出 + 开户 */
    public static final String ADMIN = "ADMIN";
    /** 二级：录入 + 提交申请 + 查看；只能改自己提交的 */
    public static final String EDITOR = "EDITOR";
    /** 三级：只读 */
    public static final String VIEWER = "VIEWER";

    private Role() {
    }

    public static boolean isAdmin(String role) {
        return ADMIN.equals(role);
    }

    /** 能不能录入（一级和二级都可以） */
    public static boolean canWrite(String role) {
        return ADMIN.equals(role) || EDITOR.equals(role);
    }
}
