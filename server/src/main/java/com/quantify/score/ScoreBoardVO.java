package com.quantify.score;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 积分总表的一行 —— 页面上显示的、导出 Excel 的，都是这个对象。
 *
 * <p>页面和导出走同一个查询，保证两边数字永远一致。
 */
@Data
public class ScoreBoardVO {

    /** 排名，按总分降序，同分并列 */
    private Integer rank;

    private Long studentId;
    private String studentNo;
    private String name;
    private Long classId;
    private String className;
    private String gradeName;

    /** 加分合计（正数） */
    private BigDecimal addScore;

    /** 扣分合计（负数，与原表"扣分"列一致） */
    private BigDecimal deductScore;

    /** 五育净分（加分-扣分），未分类记录不进这五列 */
    private BigDecimal deScore;
    private BigDecimal zhiScore;
    private BigDecimal tiScore;
    private BigDecimal meiScore;
    private BigDecimal laoScore;

    private BigDecimal baseScore;

    /** 总分 = 基础分 + 加分 + 扣分 */
    private BigDecimal totalScore;

    // ---- 导出专用：备注列拼接（格式对齐原表 '1.事项(-2)(10.17)'）----

    private String addRemark;
    private String deductRemark;
}
