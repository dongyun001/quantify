package com.quantify.log;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志。
 *
 * <p>班级评分类系统最容易起争议，光记"谁改了什么"不够，得记"从多少改成了多少"。
 * 这张表只增不改不删，所以没有任何更新字段。
 */
@Data
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 0 = 跨班级的批量操作 */
    private Long classId;

    /** 操作人所属标签，冗余存一份便于按来源查 */
    private Long deptId;

    /** SCORE_ITEM / STUDENT / USER / CONFIG */
    private String targetType;

    /** 单个操作填 id；批量操作填 0，明细看 afterJson */
    private Long targetId;

    /** CREATE/SUBMIT/APPROVE/REJECT/REVOKE/EXPORT/LOGIN/LOGIN_FAIL/... */
    private String action;

    private String beforeJson;
    private String afterJson;

    private Long operatorId;
    private String operatorIp;
    private LocalDateTime createdAt;

    // ---- join 出来的展示字段 ----

    @TableField(exist = false)
    private String operatorName;

    @TableField(exist = false)
    private String deptName;

    /** 快照里只有 studentId，查出来的展示字段：让「给谁加的分」一眼可见 */
    @TableField(exist = false)
    private String studentName;

    @TableField(exist = false)
    private String studentNo;

    @TableField(exist = false)
    private String className;
}
