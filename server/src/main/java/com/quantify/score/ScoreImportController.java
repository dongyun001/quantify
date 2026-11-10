package com.quantify.score;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.quantify.common.R;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * Excel 批量导入。一级和二级可用，三级不可见也不能调。
 *
 * <p>四个接口：下载模板 / 上传预览（不落库）/ 确认导入（整批一个事务）/ 整批撤回。
 */
@RestController
@RequestMapping("/api/scores")
@RequiredArgsConstructor
@SaCheckRole(value = {"ADMIN", "EDITOR"}, mode = SaMode.OR)
public class ScoreImportController {

    private final ScoreImportService scoreImportService;

    /** 下载导入模板 */
    @GetMapping("/import/template")
    public void template(HttpServletResponse response) throws IOException {
        scoreImportService.writeTemplate(response);
    }

    /** 上传并逐行预览，不落库 */
    @PostMapping("/import/preview")
    public R<ImportPreviewVO> preview(
            @RequestParam(value = "file", required = false) MultipartFile file) {
        return R.ok(scoreImportService.preview(file));
    }

    /** 确认批量导入 */
    @PostMapping("/batch")
    public R<Map<String, Object>> batch(@RequestBody @Valid BatchImportDTO dto) {
        return R.ok(scoreImportService.batchCreate(dto));
    }

    /** 整批撤回：仅本人的草稿 / 待审记录 */
    @PostMapping("/batch/{batchNo}/revoke")
    public R<Map<String, Object>> revoke(@PathVariable String batchNo) {
        return R.ok(scoreImportService.revokeBatch(batchNo));
    }
}
