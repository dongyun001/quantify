package com.quantify.common;

/**
 * 积分流水的状态机。
 *
 * <pre>
 * [0 草稿] ──提交──> [1 待审] ──通过──> [2 已通过]  ← 只有这个进总分
 *    ↑                  │                    │
 *    └──编辑后重提───────┘                    │
 *                       ├──驳回──> [3 已驳回] │
 *                       └──撤回──> [4 已撤销] │
 *                                            │
 * [2 已通过] ──被变更申请替代──> [5 已失效] ←──┘
 * </pre>
 *
 * <p>驳回不是删除——记录留着，学生问起来能查到"当时提交了什么、为什么被驳回"。
 */
public final class ScoreStatus {

    public static final int DRAFT = 0;
    public static final int PENDING = 1;
    public static final int APPROVED = 2;
    public static final int REJECTED = 3;
    public static final int REVOKED = 4;
    public static final int SUPERSEDED = 5;

    private ScoreStatus() {
    }

    public static String text(Integer s) {
        if (s == null) {
            return "";
        }
        return switch (s) {
            case DRAFT -> "草稿";
            case PENDING -> "待审";
            case APPROVED -> "已通过";
            case REJECTED -> "已驳回";
            case REVOKED -> "已撤销";
            case SUPERSEDED -> "已失效";
            default -> "未知";
        };
    }

    /** 只有已通过计入总分 */
    public static boolean countsTowardTotal(Integer s) {
        return s != null && s == APPROVED;
    }
}
