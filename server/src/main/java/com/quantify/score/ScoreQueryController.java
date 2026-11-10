package com.quantify.score;

import com.quantify.common.PageResult;
import com.quantify.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 积分查询：总表、学生明细、单条详情。三级都能调，范围由登录身份决定 */
@RestController
@RequestMapping("/api/scores")
@RequiredArgsConstructor
public class ScoreQueryController {

    private final ScoreQueryService scoreQueryService;

    /** 积分总表（分页，含全局排名） */
    @GetMapping("/board")
    public R<PageResult<ScoreBoardVO>> board(@RequestParam(required = false) String term,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) Long classId,
                                             @RequestParam(required = false) Long gradeId,
                                             @RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "50") long size) {
        return R.ok(scoreQueryService.board(term, keyword, classId, gradeId, page, size));
    }

    /** 某个学生的分数汇总 */
    @GetMapping("/summary/{studentId}")
    public R<ScoreBoardVO> summary(@PathVariable Long studentId,
                                   @RequestParam(required = false) String term) {
        return R.ok(scoreQueryService.studentSummary(studentId, term));
    }

    /** 某个学生的全部流水 */
    @GetMapping("/student/{studentId}")
    public R<List<ScoreItem>> studentItems(@PathVariable Long studentId,
                                           @RequestParam(required = false) String term,
                                           @RequestParam(required = false) Integer category,
                                           @RequestParam(required = false) Integer dimension,
                                           @RequestParam(required = false) Integer status) {
        return R.ok(scoreQueryService.studentItems(studentId, term, category, dimension, status));
    }

    /** 单条流水详情 */
    @GetMapping("/{id}")
    public R<ScoreItem> detail(@PathVariable Long id) {
        return R.ok(scoreQueryService.itemDetail(id));
    }
}
