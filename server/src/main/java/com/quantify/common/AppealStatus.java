package com.quantify.common;

/**
 * 量化申诉的状态机。
 *
 * <pre>
 * [0 待处理] ──受理──> [1 已受理]   （同一事务内生成变更记录，原记录置为已失效）
 *      │      ──驳回──> [2 已驳回]   （原记录不动）
 *      └──────撤回──> [3 已撤回]   （只有申诉人本人可以撤回）
 * </pre>
 *
 * <p>只有「待处理」的申诉会被 {@code active_flag} 标记，配合唯一索引
 * 保证同一条记录、同一个申诉人只有一条待处理申诉。
 */
public final class AppealStatus {

    public static final int PENDING = 0;
    public static final int ACCEPTED = 1;
    public static final int REJECTED = 2;
    public static final int WITHDRAWN = 3;

    private AppealStatus() {
    }

    public static String text(Integer s) {
        if (s == null) {
            return "";
        }
        return switch (s) {
            case PENDING -> "待处理";
            case ACCEPTED -> "已受理";
            case REJECTED -> "已驳回";
            case WITHDRAWN -> "已撤回";
            default -> "未知";
        };
    }
}
