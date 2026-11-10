package com.quantify.export;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.alibaba.excel.write.metadata.holder.WriteWorkbookHolder;
import com.alibaba.excel.write.handler.SheetWriteHandler;
import com.alibaba.excel.write.metadata.holder.WriteSheetHolder;
import com.alibaba.excel.write.style.column.SimpleColumnWidthStyleStrategy;
import com.quantify.auth.ScopeContext;
import com.quantify.common.BizException;
import com.quantify.common.ErrorCode;
import com.quantify.log.AuditLogService;
import com.quantify.org.ClassMapper;
import com.quantify.org.GradeMapper;
import com.quantify.org.SysClass;
import com.quantify.org.SysGrade;
import com.quantify.score.ScoreBoardVO;
import com.quantify.score.ScoreItemMapper;
import com.quantify.score.ScoreQueryService;
import com.quantify.setting.SysConfig;
import com.quantify.setting.SysConfigMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Excel 导出。
 *
 * <p>三条硬规则：
 * <ol>
 *   <li><b>导出和页面走同一套聚合逻辑</b>（{@link ScoreItemMapper#selectForExport}），
 *       否则会出现"页面 8 分、导出 11 分"</li>
 *   <li><b>每次导出前重设 group_concat_max_len</b>——HikariCP 复用连接，
 *       SESSION 级设置会残留，只在启动时设一次不够</li>
 *   <li><b>一个班一个 sheet</b>，不要指望一条 SQL 导出全院。
 *       4000 行一次聚合会让 GROUP_CONCAT 拼出单行几 MB 的备注，MySQL 直接卡死</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportService {

    /** 原表的列，一列不多一列不少 */
    private static final String[] COLUMNS = {
            "序号", "学号", "姓名", "加分", "加分备注", "扣分", "扣分备注",
            "德", "智", "体", "美", "劳", "基础分", "总分"
    };

    private final ScoreItemMapper scoreItemMapper;
    private final ClassMapper classMapper;
    private final GradeMapper gradeMapper;
    private final SysConfigMapper configMapper;
    private final ScoreQueryService scoreQueryService;
    private final AuditLogService auditLogService;
    private final com.quantify.score.ScoreItemService scoreItemService;

    public void export(String scope, Long classId, Long gradeId, String term, HttpServletResponse response)
            throws IOException {
        String termVal = (term == null || term.isBlank()) ? scoreQueryService.currentTerm() : term;

        List<SysClass> targets = resolveTargetClasses(scope, classId, gradeId);
        if (targets.isEmpty()) {
            throw new BizException(ErrorCode.BIZ_ERROR, "没有可导出的班级");
        }

        String fileName = buildFileName(targets, termVal);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''"
                + URLEncoder.encode(fileName, StandardCharsets.UTF_8));
        // 前端要读到文件名，必须显式暴露这个头
        response.setHeader("Access-Control-Expose-Headers", "Content-Disposition");

        try (ExcelWriter writer = EasyExcel.write(response.getOutputStream()).build()) {
            int sheetNo = 0;
            for (SysClass c : targets) {
                List<ScoreBoardVO> rows = fetchRows(c.getId(), termVal);
                WriteSheet sheet = EasyExcel.writerSheet(sheetNo++, c.getName())
                        .head(buildHead(c, termVal, rows.size()))
                        .registerWriteHandler(new TitleMergeHandler(COLUMNS.length - 1))
                        .registerWriteHandler(new ColumnWidthHandler())
                        .build();
                writer.write(toData(rows), sheet);
            }
        }

        auditLogService.record(ScopeContext.getUserId(), ScopeContext.getDeptId(), 0L,
                "SCORE_ITEM", 0L, "EXPORT", null,
                java.util.Map.of("scope", scope == null ? "class" : scope,
                        "term", termVal,
                        "classes", targets.size()));
    }

    /** 按数据范围取要导出的班级 */
    private List<SysClass> resolveTargetClasses(String scope, Long classId, Long gradeId) {
        List<Long> visible = ScopeContext.getClassIds();
        if (visible.isEmpty()) {
            return List.of();
        }
        String s = scope == null ? "class" : scope;

        // 三级账号只有本班，传什么 scope 都按本班处理
        if (visible.size() == 1) {
            return classMapper.selectBatchIds(visible);
        }
        return switch (s) {
            case "college" -> classMapper.selectList(Wrappers.<SysClass>lambdaQuery()
                    .in(SysClass::getId, visible).eq(SysClass::getStatus, 1)
                    .orderByAsc(SysClass::getGradeId).orderByAsc(SysClass::getClassNo));
            case "grade" -> {
                Long gid = gradeId;
                if (gid == null && classId != null) {
                    SysClass c = classMapper.selectById(classId);
                    gid = c == null ? null : c.getGradeId();
                }
                if (gid == null) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "请指定要导出的年级");
                }
                yield classMapper.selectList(Wrappers.<SysClass>lambdaQuery()
                        .eq(SysClass::getGradeId, gid)
                        .in(SysClass::getId, visible)
                        .eq(SysClass::getStatus, 1)
                        .orderByAsc(SysClass::getClassNo));
            }
            default -> {
                Long cid = classId;
                if (cid == null || !visible.contains(cid)) {
                    // 没指定就导范围内的第一个班
                    cid = visible.get(0);
                }
                yield classMapper.selectBatchIds(List.of(cid));
            }
        };
    }

    /**
     * 取一个班的导出数据。
     *
     * <p>{@code @Transactional} 在这里不是为了回滚，而是为了<b>把数据库连接钉住</b>——
     * {@code SET SESSION group_concat_max_len} 必须和后面的查询跑在同一条连接上才有效。
     * 不加事务的话，HikariCP 可能把这两条语句分配到不同连接，设置就白做了。
     *
     * <p>{@code GROUP_CONCAT} 默认上限 1024 字节，超了会<b>静默截断、不报错</b>，
     * 表现为导出的备注莫名其妙少了一截。docker-compose 里虽然设了全局值，
     * 但只要有人改了启动参数或换了环境，这里就是最后一道保险。
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<ScoreBoardVO> fetchRows(Long classId, String term) {
        scoreItemMapper.enableLongGroupConcat();
        return scoreItemMapper.selectForExport(classId, term);
    }

    /**
     * 两行表头：第一行是合并的大标题，第二行是列名。
     *
     * <p>标题格式对齐原表：{@code 2025-2026学年软件工程学院25级10班第一学期量化积分表}
     */
    private List<List<String>> buildHead(SysClass c, String term, int studentCount) {
        String title = buildTitle(c, term);
        List<List<String>> head = new ArrayList<>();
        for (String col : COLUMNS) {
            List<String> colHead = new ArrayList<>(2);
            colHead.add(title);
            colHead.add(col);
            head.add(colHead);
        }
        return head;
    }

    /**
     * 拼标题。学期 "2025-2026-1" → "2025-2026学年…第一学期…"
     * 拼不出学期序号时退化成简单标题，不让导出失败。
     */
    private String buildTitle(SysClass c, String term) {
        String college = config("college_name", "软件工程学院");
        String cls = c == null ? "" : c.getName();

        String academicYear = null;
        String termNo = null;
        if (term != null && term.contains("-")) {
            String[] parts = term.split("-");
            if (parts.length >= 2) {
                academicYear = parts[0] + "-" + parts[1];
            }
            if (parts.length >= 3) {
                termNo = switch (parts[2]) {
                    case "1" -> "第一学期";
                    case "2" -> "第二学期";
                    case "3" -> "第三学期";
                    default -> "第" + parts[2] + "学期";
                };
            }
        }
        if (academicYear == null || termNo == null) {
            return college + cls + "量化积分表";
        }
        return academicYear + "学年" + college + cls + termNo + "量化积分表";
    }

    private List<List<Object>> toData(List<ScoreBoardVO> rows) {
        List<List<Object>> data = new ArrayList<>(rows.size());
        int seq = 1;
        for (ScoreBoardVO r : rows) {
            List<Object> line = new ArrayList<>(COLUMNS.length);
            line.add(seq++);
            line.add(r.getStudentNo());
            line.add(r.getName());
            line.add(fmt(r.getAddScore()));
            line.add(nvl(r.getAddRemark()));
            line.add(fmt(r.getDeductScore()));
            line.add(nvl(r.getDeductRemark()));
            line.add(fmt(r.getDeScore()));
            line.add(fmt(r.getZhiScore()));
            line.add(fmt(r.getTiScore()));
            line.add(fmt(r.getMeiScore()));
            line.add(fmt(r.getLaoScore()));
            line.add(fmt(r.getBaseScore()));
            line.add(fmt(r.getTotalScore()));
            data.add(line);
        }
        return data;
    }

    /** 去掉多余的小数位：50.00 显示成 50，与原表一致 */
    private String fmt(BigDecimal v) {
        if (v == null) {
            return "0";
        }
        return v.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }

    private String buildFileName(List<SysClass> targets, String term) {
        if (targets.size() == 1) {
            return targets.get(0).getName() + " 量化积分 " + term + ".xlsx";
        }
        SysGrade g = gradeMapper.selectById(targets.get(0).getGradeId());
        String prefix = g == null ? "全院" : g.getGradeName();
        return prefix + " 量化积分 " + term + "（" + targets.size() + " 个班）.xlsx";
    }

    private String config(String key, String def) {
        SysConfig c = configMapper.selectOne(Wrappers.<SysConfig>lambdaQuery()
                .eq(SysConfig::getClassId, 0L)
                .eq(SysConfig::getConfigKey, key));
        return c == null || c.getConfigValue() == null || c.getConfigValue().isBlank()
                ? def : c.getConfigValue();
    }

    /**
     * 把表头第一行合并成一整条大标题，并加粗居中——
     * 对齐原表顶上那行"2025-2026学年…量化预公示"的观感。
     */
    private static class TitleMergeHandler implements SheetWriteHandler {
        private final int lastCol;

        TitleMergeHandler(int lastCol) {
            this.lastCol = lastCol;
        }

        @Override
        public void afterSheetCreate(WriteWorkbookHolder wbHolder, WriteSheetHolder sheetHolder) {
            org.apache.poi.ss.usermodel.Sheet sheet = sheetHolder.getSheet();
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, lastCol));

            CellStyle style = wbHolder.getWorkbook().createCellStyle();
            Font font = wbHolder.getWorkbook().createFont();
            font.setBold(true);
            font.setFontHeightInPoints((short) 14);
            style.setFont(font);
            style.setAlignment(HorizontalAlignment.CENTER);
            style.setVerticalAlignment(VerticalAlignment.CENTER);

            Row titleRow = sheet.getRow(0);
            if (titleRow == null) {
                titleRow = sheet.createRow(0);
            }
            titleRow.setHeightInPoints(28);
            for (int i = 0; i <= lastCol; i++) {
                Cell cell = titleRow.getCell(i);
                if (cell == null) {
                    cell = titleRow.createCell(i);
                }
                cell.setCellStyle(style);
            }

            Row headRow = sheet.getRow(1);
            if (headRow != null) {
                CellStyle headStyle = wbHolder.getWorkbook().createCellStyle();
                Font hf = wbHolder.getWorkbook().createFont();
                hf.setBold(true);
                headStyle.setFont(hf);
                headStyle.setAlignment(HorizontalAlignment.CENTER);
                for (int i = 0; i <= lastCol; i++) {
                    Cell cell = headRow.getCell(i);
                    if (cell != null) {
                        cell.setCellStyle(headStyle);
                    }
                }
            }
        }
    }

    /**
     * 逐列设置列宽。
     * 原表里"序号"很窄、"备注"很宽，统一宽度的话备注被挤成一团、序号又占太多地方。
     */
    private static class ColumnWidthHandler implements SheetWriteHandler {
        /** 与 COLUMNS 一一对应 */
        private static final int[] WIDTHS = {6, 16, 10, 8, 46, 8, 46, 8, 8, 8, 8, 8, 8, 8};

        @Override
        public void afterSheetCreate(WriteWorkbookHolder wbHolder, WriteSheetHolder sheetHolder) {
            org.apache.poi.ss.usermodel.Sheet sheet = sheetHolder.getSheet();
            for (int i = 0; i < WIDTHS.length; i++) {
                sheet.setColumnWidth(i, WIDTHS[i] * 256);
            }
        }
    }
}
