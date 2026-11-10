package com.quantify.score;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.alibaba.excel.write.metadata.style.WriteCellStyle;
import com.alibaba.excel.write.metadata.style.WriteFont;
import com.alibaba.excel.write.style.HorizontalCellStyleStrategy;
import com.alibaba.excel.write.style.column.LongestMatchColumnWidthStyleStrategy;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.quantify.auth.ScopeContext;
import com.quantify.common.BizException;
import com.quantify.common.ErrorCode;
import com.quantify.common.ScoreStatus;
import com.quantify.log.AuditLogService;
import com.quantify.org.ClassMapper;
import com.quantify.org.SysClass;
import com.quantify.student.Student;
import com.quantify.student.StudentMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Excel 批量导入。
 *
 * <p>三个阶段：下载模板 → 上传预览（<b>不落库</b>）→ 确认导入（整批一个事务）。
 *
 * <p>几条硬约束：
 * <ul>
 *   <li>表头按<b>列名</b>定位，不按列序号——用户会调整列顺序</li>
 *   <li>{@code points} 恒为正数，方向由 category 决定（AGENTS.md R1）</li>
 *   <li>数据范围只认登录身份，跨范围返回 403 语义而不是静默跳过（R6/R7）</li>
 *   <li>整批一个事务 + 每批一条 BATCH_IMPORT 日志，不逐行写（否则 2000 行冲爆日志表）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScoreImportService {

    private static final int MAX_ROWS = 2000;
    private static final int MAX_REASON_LENGTH = 500;
    private static final BigDecimal MAX_POINTS = new BigDecimal("9999.99");
    private static final DateTimeFormatter BATCH_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    // ---- 模板列 ----

    private static final String COL_NO = "学号";
    private static final String COL_NAME = "姓名";
    private static final String COL_DIMENSION = "类别";
    private static final String COL_TYPE = "类型";
    private static final String COL_POINTS = "分值";
    private static final String COL_REASON = "事由";
    private static final String COL_DATE = "发生日期";

    /** 输出顺序，同时也是表头匹配的优先顺序 */
    private static final List<String> COLUMNS =
            List.of(COL_NO, COL_NAME, COL_DIMENSION, COL_TYPE,
                    COL_POINTS, COL_REASON, COL_DATE);

    /** 模板列名可能被写成这些同义词 */
    private static final Map<String, List<String>> ALIASES = new LinkedHashMap<>();

    static {
        ALIASES.put(COL_NO, List.of("学号", "学生学号", "学籍号"));
        ALIASES.put(COL_NAME, List.of("姓名", "学生姓名"));
        ALIASES.put(COL_DIMENSION, List.of("类别", "五育", "维度"));
        ALIASES.put(COL_TYPE, List.of("类型", "积分类型"));
        ALIASES.put(COL_POINTS, List.of("分值", "分数", "分值数"));
        ALIASES.put(COL_REASON, List.of("事由", "原因", "事项"));
        ALIASES.put(COL_DATE, List.of("发生日期", "日期", "时间"));
    }

    /** 姓名、发生日期可以没有列；其余四列缺失直接报错 */
    private static final List<String> REQUIRED_COLUMNS =
            List.of(COL_NO, COL_DIMENSION, COL_TYPE, COL_POINTS, COL_REASON);

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy-M-d"),
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy.M.d"),
            DateTimeFormatter.ofPattern("yyyy年M月d日"),
            DateTimeFormatter.ofPattern("yyyyMMdd")
    );

    private static final List<DateTimeFormatter> DATE_TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-M-d H:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/M/d H:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-M-d H:mm"),
            DateTimeFormatter.ofPattern("yyyy/M/d H:mm"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
    );

    private final ScoreItemMapper scoreItemMapper;
    private final StudentMapper studentMapper;
    private final ClassMapper classMapper;
    private final ScoreItemService scoreItemService;
    private final AuditLogService auditLogService;

    // ==================================================================
    //  模板下载
    // ==================================================================

    public void writeTemplate(HttpServletResponse response) throws IOException {
        String filename = URLEncoder.encode("批量导入模板.xlsx", StandardCharsets.UTF_8)
                .replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment;filename*=UTF-8''" + filename);

        List<List<String>> head = new ArrayList<>();
        for (String col : COLUMNS) {
            head.add(List.of(col));
        }

        // 真实学号 + 「示例：」前缀：用户忘了删时，预览会明确提示
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("25560641001", "刘一凡", "体", "扣分", 2, "示例：晚自习未到", "2025-10-14"));
        rows.add(List.of("25560641002", "董昌宇", "智", "加分", 6, "示例：育英杯双语朗诵比赛三等奖", ""));
        rows.add(List.of("25560641003", "丁锡庆", "美", "加分", 3, "示例：元旦晚会志愿者", "2025-12-28"));

        WriteCellStyle headStyle = new WriteCellStyle();
        WriteFont font = new WriteFont();
        font.setBold(true);
        headStyle.setWriteFont(font);
        headStyle.setHorizontalAlignment(HorizontalAlignment.CENTER);

        EasyExcel.write(response.getOutputStream())
                .head(head)
                .registerWriteHandler(new HorizontalCellStyleStrategy(headStyle, new WriteCellStyle()))
                .registerWriteHandler(new LongestMatchColumnWidthStyleStrategy())
                .sheet("批量导入")
                .doWrite(rows);
    }

    // ==================================================================
    //  上传预览
    // ==================================================================

    /** 解析并逐行校验，<b>不写任何数据</b>。 */
    public ImportPreviewVO preview(MultipartFile file) {
        validateFile(file);

        SheetData sheet = readSheet(file);
        int[] columns = resolveColumns(sheet.head);

        List<ParsedRow> parsed = new ArrayList<>();
        for (RawRow raw : sheet.rows) {
            ParsedRow row = parseRow(raw, columns);
            if (!row.blank) {
                parsed.add(row);
            }
        }
        if (parsed.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件里没有数据行");
        }
        if (parsed.size() > MAX_ROWS) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "单次最多导入 " + MAX_ROWS + " 行，请分批导入（当前 " + parsed.size() + " 行）");
        }

        Map<String, List<Student>> students = loadStudents(parsed);
        Map<Long, String> classNames = loadClassNames(students);
        String term = scoreItemService.resolveTerm(null);

        for (ParsedRow row : parsed) {
            resolveStudent(row, students, classNames);
        }

        Map<String, Integer> existing = loadExistingDuplicates(
                parsed.stream().map(r -> r.studentId).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet()),
                term);

        markInternalDuplicates(parsed);
        for (ParsedRow row : parsed) {
            markTemplateExample(row);
            markExistingDuplicate(row, existing);
        }

        return buildPreview(parsed);
    }

    private ImportPreviewVO buildPreview(List<ParsedRow> parsed) {
        ImportPreviewVO vo = new ImportPreviewVO();
        int valid = 0;

        for (ParsedRow p : parsed) {
            ImportPreviewVO.Row row = new ImportPreviewVO.Row();
            row.setRowNo(p.rowNo);
            row.setStudentNo(p.studentNo);
            row.setStudentName(p.enteredName.isBlank() ? p.dbName : p.enteredName);
            row.setStudentId(p.studentId);
            row.setClassName(p.className);
            row.setDimension(p.dimension);
            row.setDimensionText(ScoreItemService.dimensionText(p.dimension));
            row.setCategory(p.category);
            row.setPoints(p.points);
            row.setReason(p.reason);
            row.setOccurredOn(p.occurredOn);
            row.setError(p.errors.isEmpty() ? null : String.join("；", p.errors));
            row.setWarning(p.warnings.isEmpty() ? null : String.join("；", p.warnings));
            row.setIdentityInvalid(p.identityInvalid);

            vo.getRows().add(row);
            if (p.errors.isEmpty()) {
                valid++;
            } else {
                vo.getErrors().add(new ImportPreviewVO.RowError(p.rowNo, String.join("；", p.errors)));
            }
        }

        vo.setTotal(parsed.size());
        vo.setValidCount(valid);
        vo.setInvalidCount(parsed.size() - valid);
        vo.setCanImport(valid == parsed.size());
        return vo;
    }

    // ==================================================================
    //  确认导入
    // ==================================================================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> batchCreate(BatchImportDTO dto) {
        List<BatchImportDTO.Item> items = dto.getItems();
        if (items == null || items.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请至少提供一条记录");
        }
        if (items.size() > MAX_ROWS) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "单次最多导入 " + MAX_ROWS + " 条，请分批导入");
        }

        String term = scoreItemService.resolveTerm(null);
        boolean effectNow = scoreItemService.resolveEffectNow(dto.getEffectNow());
        boolean submitNow = Boolean.TRUE.equals(dto.getSubmitNow());
        // 直接生效优先：一级选了直接生效时，submitNow 没有意义
        int status = effectNow ? ScoreStatus.APPROVED
                : (submitNow ? ScoreStatus.PENDING : ScoreStatus.DRAFT);

        String batchNo = generateBatchNo();

        for (int i = 0; i < items.size(); i++) {
            BatchImportDTO.Item item = items.get(i);
            int line = i + 1;
            validateBatchItem(item, line);

            Student stu;
            try {
                stu = scoreItemService.requireWritableStudent(item.getStudentId());
            } catch (BizException e) {
                throw new BizException(e.getErrorCode(), "第 " + line + " 条记录：" + e.getMessage());
            }

            ScoreItemDTO dtoItem = new ScoreItemDTO();
            dtoItem.setStudentId(item.getStudentId());
            dtoItem.setDimension(item.getDimension());
            dtoItem.setCategory(item.getCategory());
            dtoItem.setPoints(item.getPoints());
            dtoItem.setReason(item.getReason().trim());
            dtoItem.setOccurredOn(item.getOccurredOn());

            scoreItemService.insertNew(dtoItem, stu, term, status,
                    status == ScoreStatus.PENDING, batchNo);
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("batchNo", batchNo);
        after.put("created", items.size());
        after.put("status", status);
        after.put("submitNow", submitNow);
        after.put("effectNow", effectNow);
        auditLogService.record(ScopeContext.getUserId(), ScopeContext.getDeptId(), 0L,
                "SCORE_BATCH", 0L, "BATCH_IMPORT", null, after);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchNo", batchNo);
        result.put("created", items.size());
        result.put("status", status);
        result.put("statusText", ScoreStatus.text(status));
        return result;
    }

    private void validateBatchItem(BatchImportDTO.Item item, int line) {
        if (item.getDimension() == null || item.getDimension() < 1 || item.getDimension() > 5) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "第 " + line + " 条记录：请选择类别（德/智/体/美/劳）");
        }
        if (item.getCategory() == null || item.getCategory() < 1 || item.getCategory() > 2) {
            throw new BizException(ErrorCode.BAD_REQUEST, "第 " + line + " 条记录：类型只能是 加分/扣分");
        }
        if (item.getPoints() == null || item.getPoints().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "第 " + line + " 条记录：分值必须为正数，扣分请把类型选为「扣分」");
        }
        if (item.getPoints().stripTrailingZeros().scale() > 2) {
            throw new BizException(ErrorCode.BAD_REQUEST, "第 " + line + " 条记录：分值最多 2 位小数");
        }
        if (item.getPoints().compareTo(MAX_POINTS) > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "第 " + line + " 条记录：分值超出允许范围（最大 " + MAX_POINTS + "）");
        }
        if (item.getReason() == null || item.getReason().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "第 " + line + " 条记录：事由不能为空");
        }
        if (item.getReason().trim().length() > MAX_REASON_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "第 " + line + " 条记录：事由不能超过 " + MAX_REASON_LENGTH + " 字");
        }
    }

    // ==================================================================
    //  整批撤回
    // ==================================================================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> revokeBatch(String batchNo) {
        if (batchNo == null || batchNo.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "批次号不能为空");
        }

        List<ScoreItem> items = scoreItemMapper.selectList(Wrappers.<ScoreItem>lambdaQuery()
                .eq(ScoreItem::getBatchNo, batchNo));
        if (items.isEmpty()) {
            throw BizException.notFound("批次不存在");
        }
        for (ScoreItem item : items) {
            if (!ScopeContext.canSee(item.getClassId())) {
                throw new BizException(ErrorCode.FORBIDDEN, "无权限操作该批次");
            }
        }

        int revoked = scoreItemMapper.update(null, Wrappers.<ScoreItem>lambdaUpdate()
                .eq(ScoreItem::getBatchNo, batchNo)
                .eq(ScoreItem::getCreatedBy, ScopeContext.getUserId())
                .in(ScoreItem::getStatus, ScoreStatus.DRAFT, ScoreStatus.PENDING)
                .set(ScoreItem::getStatus, ScoreStatus.REVOKED));

        if (revoked == 0) {
            throw BizException.of("该批次没有可撤回的记录（已通过的需要走变更申请）");
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("batchNo", batchNo);
        after.put("revoked", revoked);
        auditLogService.record(ScopeContext.getUserId(), ScopeContext.getDeptId(), 0L,
                "SCORE_BATCH", 0L, "REVOKE", null, after);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchNo", batchNo);
        result.put("revoked", revoked);
        return result;
    }

    // ==================================================================
    //  Excel 读取
    // ==================================================================

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请上传 Excel 文件");
        }
        String name = file.getOriginalFilename();
        String lower = name == null ? "" : name.toLowerCase();
        if (!lower.endsWith(".xlsx") && !lower.endsWith(".xls")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只支持 .xlsx / .xls 文件");
        }
        if (file.getSize() > 20L * 1024 * 1024) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件不能超过 20MB");
        }
    }

    private SheetData readSheet(MultipartFile file) {
        SheetData listener = new SheetData();
        try (InputStream in = file.getInputStream()) {
            EasyExcel.read(in, listener).headRowNumber(1).sheet().doRead();
        } catch (Exception e) {
            log.warn("解析 Excel 失败: {}", file.getOriginalFilename(), e);
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "Excel 无法解析，请确认文件未损坏且为 xlsx / xls 格式");
        }
        if (listener.head == null || listener.head.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少表头行");
        }
        return listener;
    }

    /**
     * 按列名定位列序号。
     * <b>不能按序号</b>——用户很可能把「事由」拖到「分值」前面，列一错位分会安静地记错。
     */
    private int[] resolveColumns(Map<Integer, String> head) {
        Map<String, Integer> found = new HashMap<>();
        for (Map.Entry<Integer, String> entry : head.entrySet()) {
            String name = normalizeHeader(entry.getValue());
            if (name.isEmpty()) {
                continue;
            }
            for (String column : COLUMNS) {
                if (found.containsKey(column)) {
                    continue;
                }
                for (String alias : ALIASES.get(column)) {
                    if (name.equals(alias) || name.startsWith(alias + "(") || name.startsWith(alias + "（")) {
                        found.put(column, entry.getKey());
                        break;
                    }
                }
            }
        }

        List<String> missing = REQUIRED_COLUMNS.stream()
                .filter(c -> !found.containsKey(c))
                .toList();
        if (!missing.isEmpty()) {
            // 旧模板没有「类别」：单独给明确提示，避免用户以为只是少了无关列
            if (missing.contains(COL_DIMENSION)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "缺少「类别」列，请下载最新模板");
            }
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少必需列：" + String.join("、", missing));
        }

        int[] result = new int[COLUMNS.size()];
        for (int i = 0; i < COLUMNS.size(); i++) {
            result[i] = found.getOrDefault(COLUMNS.get(i), -1);
        }
        return result;
    }

    private String normalizeHeader(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\u3000", "")
                .replace("*", "")
                .replaceAll("\\s+", "")
                .trim();
    }

    private static class SheetData extends AnalysisEventListener<Map<Integer, String>> {

        private Map<Integer, String> head;
        private final List<RawRow> rows = new ArrayList<>();

        @Override
        public void invokeHeadMap(Map<Integer, String> headMap, AnalysisContext context) {
            this.head = headMap;
        }

        @Override
        public void invoke(Map<Integer, String> data, AnalysisContext context) {
            int rowNo = context.readRowHolder().getRowIndex() + 1;
            rows.add(new RawRow(rowNo, data));
        }

        @Override
        public void doAfterAllAnalysed(AnalysisContext context) {
            // 无需收尾
        }
    }

    private record RawRow(int rowNo, Map<Integer, String> cells) {
    }

    // ==================================================================
    //  逐行解析
    // ==================================================================

    private ParsedRow parseRow(RawRow raw, int[] columns) {
        ParsedRow p = new ParsedRow();
        p.rowNo = raw.rowNo;
        p.studentNo = normalizeStudentNo(cell(raw.cells, columns[0]));
        p.enteredName = cell(raw.cells, columns[1]);
        p.rawDimension = cell(raw.cells, columns[2]);
        p.rawCategory = cell(raw.cells, columns[3]);
        p.rawPoints = cell(raw.cells, columns[4]);
        p.reason = cell(raw.cells, columns[5]);
        p.rawDate = cell(raw.cells, columns[6]);

        if (p.studentNo.isEmpty() && p.enteredName.isEmpty() && p.rawDimension.isEmpty()
                && p.rawCategory.isEmpty() && p.rawPoints.isEmpty()
                && p.reason.isEmpty() && p.rawDate.isEmpty()) {
            p.blank = true;
            return p;
        }

        if (p.studentNo.isEmpty()) {
            p.errors.add("学号不能为空");
            p.identityInvalid = true;
        }

        p.dimension = parseDimension(p.rawDimension, p.errors);
        p.category = parseCategory(p.rawCategory, p.errors);
        p.points = parsePoints(p.rawPoints, p.errors);
        validateReason(p.reason, p.errors);
        p.occurredOn = parseDate(p.rawDate, p.errors);
        return p;
    }

    private String cell(Map<Integer, String> cells, int index) {
        if (index < 0 || cells == null) {
            return "";
        }
        String value = cells.get(index);
        return value == null ? "" : value.trim();
    }

    /** Excel 把 11 位学号当数字时可能读成 2.5560641001E10 或 25560641001.0 */
    private String normalizeStudentNo(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.matches("(?i)^\\d+(\\.\\d+)?E[+-]?\\d+$")
                || value.matches("^\\d+\\.0+$")) {
            try {
                return new BigDecimal(value).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException ignored) {
                return value;
            }
        }
        return value;
    }

    /** 五育类别：德/智/体/美/劳，也接受 1~5 */
    private Integer parseDimension(String raw, List<String> errors) {
        if (raw == null || raw.isBlank()) {
            errors.add("类别不能为空（德/智/体/美/劳）");
            return null;
        }
        String value = raw.trim();
        return switch (value) {
            case "德", "1", "1.0" -> 1;
            case "智", "2", "2.0" -> 2;
            case "体", "3", "3.0" -> 3;
            case "美", "4", "4.0" -> 4;
            case "劳", "5", "5.0" -> 5;
            default -> {
                errors.add("类别只能是 德/智/体/美/劳（填的是「" + value + "」）");
                yield null;
            }
        };
    }

    private Integer parseCategory(String raw, List<String> errors) {
        if (raw == null || raw.isBlank()) {
            errors.add("类型不能为空（只能是 加分/扣分）");
            return null;
        }
        String value = raw.trim();
        return switch (value) {
            case "加分", "1", "1.0" -> 1;
            case "扣分", "2", "2.0" -> 2;
            default -> {
                errors.add("类型只能是 加分/扣分（填的是「" + value + "」）");
                yield null;
            }
        };
    }

    private BigDecimal parsePoints(String raw, List<String> errors) {
        if (raw == null || raw.isBlank()) {
            errors.add("分值不能为空");
            return null;
        }
        BigDecimal value;
        try {
            value = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            errors.add("分值必须是数字（填的是「" + raw.trim() + "」）");
            return null;
        }
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            errors.add("分值必须为正数，扣分请把类型选为「扣分」");
            return null;
        }
        if (value.stripTrailingZeros().scale() > 2) {
            errors.add("分值最多 2 位小数（填的是 " + raw.trim() + "）");
            return null;
        }
        if (value.compareTo(MAX_POINTS) > 0) {
            errors.add("分值超出允许范围（最大 " + MAX_POINTS + "）");
            return null;
        }
        return value;
    }

    private void validateReason(String reason, List<String> errors) {
        if (reason == null || reason.isBlank()) {
            errors.add("事由不能为空");
            return;
        }
        if (reason.trim().length() > MAX_REASON_LENGTH) {
            errors.add("事由不能超过 " + MAX_REASON_LENGTH + " 字（当前 " + reason.trim().length() + " 字）");
        }
    }

    private LocalDate parseDate(String raw, List<String> errors) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();

        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, format);
            } catch (DateTimeParseException ignored) {
                // 继续尝试下一种格式
            }
        }
        for (DateTimeFormatter format : DATE_TIME_FORMATS) {
            try {
                return LocalDateTime.parse(value, format).toLocalDate();
            } catch (DateTimeParseException ignored) {
                // 继续尝试下一种格式
            }
        }
        // Excel 日期序列号：1899-12-30 起算
        try {
            double serial = Double.parseDouble(value);
            if (serial >= 20000 && serial <= 80000) {
                return LocalDate.of(1899, 12, 30).plusDays((long) serial);
            }
        } catch (NumberFormatException ignored) {
            // 不是序列号
        }

        errors.add("日期格式无法解析：「" + raw + "」（应为 yyyy-MM-dd）");
        return null;
    }

    // ==================================================================
    //  学生解析 / 重复检查
    // ==================================================================

    private Map<String, List<Student>> loadStudents(List<ParsedRow> rows) {
        Set<String> numbers = new HashSet<>();
        for (ParsedRow row : rows) {
            if (!row.studentNo.isEmpty()) {
                numbers.add(row.studentNo);
            }
        }
        if (numbers.isEmpty()) {
            return Map.of();
        }

        List<Student> students = studentMapper.selectList(Wrappers.<Student>lambdaQuery()
                .in(Student::getStudentNo, numbers));
        Map<String, List<Student>> byNo = new HashMap<>();
        for (Student student : students) {
            byNo.computeIfAbsent(student.getStudentNo(), k -> new ArrayList<>()).add(student);
        }
        return byNo;
    }

    private Map<Long, String> loadClassNames(Map<String, List<Student>> students) {
        Set<Long> classIds = new HashSet<>();
        for (List<Student> list : students.values()) {
            for (Student student : list) {
                if (student.getClassId() != null) {
                    classIds.add(student.getClassId());
                }
            }
        }
        if (classIds.isEmpty()) {
            return Map.of();
        }

        List<SysClass> classes = classMapper.selectBatchIds(classIds);
        Map<Long, String> names = new HashMap<>();
        for (SysClass cls : classes) {
            names.put(cls.getId(), cls.getName());
        }
        return names;
    }

    private void resolveStudent(ParsedRow row, Map<String, List<Student>> students,
                                Map<Long, String> classNames) {
        if (row.studentNo.isEmpty()) {
            return;
        }

        List<Student> candidates = students.getOrDefault(row.studentNo, List.of());
        if (candidates.isEmpty()) {
            row.errors.add("学号 " + row.studentNo + " 不存在");
            row.identityInvalid = true;
            return;
        }

        List<Student> inScope = candidates.stream()
                .filter(s -> ScopeContext.canSee(s.getClassId()))
                .toList();
        if (inScope.isEmpty()) {
            row.errors.add("学号 " + row.studentNo + " 不在你的数据范围内");
            row.identityInvalid = true;
            return;
        }
        if (inScope.size() > 1) {
            row.errors.add("学号 " + row.studentNo + " 对应多个班级的学生，请联系管理员处理");
            row.identityInvalid = true;
            return;
        }

        Student student = inScope.get(0);
        if (student.getStatus() == null || student.getStatus() != 1) {
            row.errors.add("学号 " + row.studentNo + " 的学生已离班");
            row.identityInvalid = true;
            return;
        }

        // 姓名是「填了就校验」：学号打错一个字，分会安静地加到别人头上
        if (!row.enteredName.isBlank() && !row.enteredName.equals(student.getName())) {
            row.errors.add("姓名与学号不匹配：该学号对应「" + student.getName()
                    + "」，表里填的是「" + row.enteredName + "」");
            row.identityInvalid = true;
        }

        row.studentId = student.getId();
        row.dbName = student.getName();
        row.className = classNames.get(student.getClassId());
    }

    /** 批量重复检查委托给 ScoreItemService，口径与单条录入完全一致 */
    private Map<String, Integer> loadExistingDuplicates(Collection<Long> studentIds, String term) {
        return scoreItemService.duplicateCounts(studentIds, term);
    }

    /**
     * 同一文件内重复：只给<b>后出现</b>的行加警告，并指出第一行在哪。
     * 不阻断——同一学生同一天两条相同事由并非不可能。
     */
    private void markInternalDuplicates(List<ParsedRow> rows) {
        Map<String, Integer> firstRow = new LinkedHashMap<>();
        for (ParsedRow row : rows) {
            if (row.studentNo.isEmpty() || row.category == null || row.dimension == null
                    || row.reason == null || row.reason.isBlank()) {
                continue;
            }
            String date = row.occurredOn != null ? row.occurredOn.toString() : row.rawDate;
            String key = row.studentNo + "|" + row.category + "|" + row.dimension
                    + "|" + row.reason + "|" + date;
            Integer first = firstRow.putIfAbsent(key, row.rowNo);
            if (first != null) {
                row.warnings.add("第 " + first + " 行与第 " + row.rowNo
                        + " 行重复（同一学号、同一类型、同一类别、同一事由、同一日期）");
            }
        }
    }

    private void markTemplateExample(ParsedRow row) {
        if (row.reason != null
                && (row.reason.startsWith("示例：") || row.reason.startsWith("示例:"))) {
            row.warnings.add("这是模板示例行，请删除后再导入");
        }
    }

    private void markExistingDuplicate(ParsedRow row, Map<String, Integer> existing) {
        if (row.studentId == null || row.category == null
                || row.dimension == null || row.reason == null) {
            return;
        }
        Integer count = existing.get(
                ScoreItemService.duplicateKey(row.studentId, row.category,
                        row.dimension, row.reason));
        if (count == null || count == 0) {
            return;
        }
        String period = row.occurredOn == null ? "本学期" : row.occurredOn.format(MONTH_FORMAT);
        row.warnings.add("该学生在 " + period + " 已有 " + count + " 条相同事由的记录，请确认是否重复录入");
    }

    // ==================================================================
    //  内部结构
    // ==================================================================

    private static String generateBatchNo() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 4);
        return LocalDateTime.now().format(BATCH_NO_FORMAT) + "-" + suffix;
    }

    private static class ParsedRow {
        private int rowNo;
        private boolean blank;
        private String studentNo = "";
        private String enteredName = "";
        private String rawDimension = "";
        private String rawCategory = "";
        private String rawPoints = "";
        private String reason = "";
        private String rawDate = "";
        private Integer dimension;
        private Integer category;
        private BigDecimal points;
        private LocalDate occurredOn;
        private Long studentId;
        private String dbName;
        private String className;
        private boolean identityInvalid;
        private final List<String> errors = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
    }
}
