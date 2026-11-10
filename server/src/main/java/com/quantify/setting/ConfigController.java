package com.quantify.setting;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.quantify.auth.ScopeContext;
import com.quantify.common.BizException;
import com.quantify.common.R;
import com.quantify.log.AuditLogService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/** 系统配置。仅一级可改，所有人可读（前端要拿当前学期等） */
@RestController
@RequestMapping("/api/configs")
@RequiredArgsConstructor
public class ConfigController {

    private final SysConfigMapper configMapper;
    private final AuditLogService auditLogService;

    @GetMapping
    public R<List<SysConfig>> list() {
        return R.ok(configMapper.selectList(Wrappers.<SysConfig>lambdaQuery()
                .eq(SysConfig::getClassId, 0L).orderByAsc(SysConfig::getId)));
    }

    @PutMapping
    @SaCheckRole("ADMIN")
    public R<Void> update(@RequestBody ItemsDTO dto) {
        if (dto.getItems() == null || dto.getItems().isEmpty()) {
            return R.ok();
        }
        for (Item it : dto.getItems()) {
            SysConfig exist = configMapper.selectOne(Wrappers.<SysConfig>lambdaQuery()
                    .eq(SysConfig::getClassId, 0L)
                    .eq(SysConfig::getConfigKey, it.getKey()));
            if (exist == null) {
                throw new BizException(com.quantify.common.ErrorCode.BAD_REQUEST,
                        "配置项不存在：" + it.getKey());
            }
            // default_base_score 必须是数字，否则后面算分要炸
            if ("default_base_score".equals(it.getKey())) {
                try {
                    Double.parseDouble(it.getValue());
                } catch (Exception e) {
                    throw new BizException(com.quantify.common.ErrorCode.BAD_REQUEST,
                            "默认基础分必须是数字");
                }
            }
            String before = exist.getConfigValue();
            configMapper.update(null, Wrappers.<SysConfig>lambdaUpdate()
                    .eq(SysConfig::getId, exist.getId())
                    .set(SysConfig::getConfigValue, it.getValue())
                    .set(SysConfig::getUpdatedBy, ScopeContext.getUserId())
                    .set(SysConfig::getUpdatedAt, LocalDateTime.now()));

            auditLogService.record(ScopeContext.getUserId(), ScopeContext.getDeptId(), 0L,
                    "CONFIG", exist.getId(), "UPDATE",
                    java.util.Map.of("key", it.getKey(), "value", String.valueOf(before)),
                    java.util.Map.of("key", it.getKey(), "value", String.valueOf(it.getValue())));
        }
        return R.ok();
    }

    @Data
    public static class ItemsDTO {
        private List<Item> items;
    }

    @Data
    public static class Item {
        private String key;
        private String value;
    }
}
