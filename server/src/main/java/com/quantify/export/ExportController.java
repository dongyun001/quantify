package com.quantify.export;

import com.quantify.common.R;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * 导出 Excel。
 *
 * <p>成功时返回二进制流；出错时返回 JSON 错误体（前端按 Content-Type 区分）。
 */
@RestController
@RequestMapping("/api/export")
@RequiredArgsConstructor
public class ExportController {

    private final ExportService exportService;

    /**
     * @param scope class（默认）/ grade / college。三级账号传什么都按本班处理
     */
    @GetMapping("/scores")
    public void export(@RequestParam(required = false) String scope,
                       @RequestParam(required = false) Long classId,
                       @RequestParam(required = false) Long gradeId,
                       @RequestParam(required = false) String term,
                       HttpServletResponse response) throws IOException {
        exportService.export(scope, classId, gradeId, term, response);
    }
}
