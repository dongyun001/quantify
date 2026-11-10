package com.quantify.score;

import com.quantify.auth.ScopeContext;
import com.quantify.common.BizException;
import com.quantify.common.PageResult;
import com.quantify.common.ErrorCode;
import com.quantify.setting.SysConfig;
import com.quantify.setting.SysConfigMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ScoreQueryService {

    private final ScoreItemMapper scoreItemMapper;
    private final SysConfigMapper configMapper;

    /** 当前学期，取配置里的值 */
    public String currentTerm() {
        SysConfig c = configMapper.selectOne(Wrappers.<SysConfig>lambdaQuery()
                .eq(SysConfig::getClassId, 0L)
                .eq(SysConfig::getConfigKey, "current_term"));
        return c == null || c.getConfigValue() == null || c.getConfigValue().isBlank()
                ? "2025-2026-1" : c.getConfigValue();
    }

    /** 单页最大条数。前端传再大也不给，避免有人 page=1&size=99999 把服务拖垮 */
    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 50;

    /**
     * 积分总表（分页）。
     *
     * <p>范围过滤只认登录身份——前端传的 classId 落在范围内才采纳。
     *
     * <p>注意 <b>rank 是全局排名</b>：SQL 里 ROW_NUMBER 在分页之前算完，
     * 所以第 2 页的第一行显示的是第 51 名，不是重新从 1 开始。
     */
    public PageResult<ScoreBoardVO> board(String term, String keyword, Long classId,
                                          Long gradeId, long page, long size) {
        long p = Math.max(1, page);
        long s = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);

        List<Long> scope = ScopeContext.getClassIds();
        if (scope.isEmpty()) {
            return PageResult.of(0, p, s, List.of());
        }
        // 前端传的 classId 必须在可见范围内才采纳，否则忽略（不报错）
        Long effectiveClassId = (classId != null && scope.contains(classId)) ? classId : null;
        String t = termOrCurrent(term);

        long total = scoreItemMapper.countBoard(scope, t, keyword, effectiveClassId, gradeId);
        if (total == 0) {
            return PageResult.of(0, p, s, List.of());
        }
        List<ScoreBoardVO> list = scoreItemMapper.selectBoard(
                scope, t, keyword, effectiveClassId, gradeId, (p - 1) * s, s);
        return PageResult.of(total, p, s, list);
    }

    public ScoreBoardVO studentSummary(Long studentId, String term) {
        ScoreBoardVO vo = scoreItemMapper.selectStudentSummary(studentId, termOrCurrent(term));
        if (vo == null) {
            throw BizException.notFound("学生不存在");
        }
        if (!ScopeContext.canSee(vo.getClassId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权限查看该学生");
        }
        return vo;
    }

    /** 某个学生的全部流水 */
    public List<ScoreItem> studentItems(Long studentId, String term, Integer category,
                                        Integer dimension, Integer status) {
        // 先做范围校验，避免通过 studentId 越权查看别班学生
        studentSummary(studentId, term);
        List<ScoreItem> list = scoreItemMapper.selectItems(
                studentId, termOrCurrent(term), category, dimension, status);
        for (ScoreItem item : list) {
            item.setDimensionText(ScoreItemService.dimensionText(item.getDimension()));
        }
        return list;
    }

    public ScoreItem itemDetail(Long id) {
        ScoreItem item = scoreItemMapper.selectItemById(id);
        if (item == null) {
            throw BizException.notFound("记录不存在");
        }
        if (!ScopeContext.canSee(item.getClassId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权限查看该记录");
        }
        item.setDimensionText(ScoreItemService.dimensionText(item.getDimension()));
        return item;
    }

    private String termOrCurrent(String term) {
        return (term == null || term.isBlank()) ? currentTerm() : term;
    }
}
