package com.quantify.score;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量导入预览结果。
 *
 * <p>预览<b>不落库</b>，只把每一行的解析结果和错误原因返回给前端。
 * 4000 人的名单里错 3 行，如果只回一句"导入失败"，用户根本不知道去哪找。
 */
@Data
public class ImportPreviewVO {

    /** 数据行数（完全空白的行不计） */
    private int total;
    private int validCount;
    private int invalidCount;
    private boolean canImport;

    private List<Row> rows = new ArrayList<>();
    private List<RowError> errors = new ArrayList<>();

    @Data
    public static class Row {

        /** Excel 行号：表头是第 1 行，首条数据是第 2 行 */
        private int rowNo;
        private String studentNo;
        private String studentName;
        /** 学号解析成功时才有值；学号有问题时为 null */
        private Long studentId;
        private String className;
        private Integer dimension;
        private String dimensionText;
        private Integer category;
        private BigDecimal points;
        private String reason;
        private LocalDate occurredOn;

        /** 该行的错误，多个用「；」连接；无错误为 null */
        private String error;
        /** 不阻断导入的提示；无警告为 null */
        private String warning;

        /**
         * 学号/姓名相关错误（不存在、已离班、跨范围、姓名不匹配）。
         * 这类问题不能在预览页编辑修正，前端据此保持"不可修"的错误提示。
         */
        private boolean identityInvalid;
    }

    @Data
    public static class RowError {
        private int rowNo;
        private String message;

        public RowError() {
        }

        public RowError(int rowNo, String message) {
            this.rowNo = rowNo;
            this.message = message;
        }
    }
}
