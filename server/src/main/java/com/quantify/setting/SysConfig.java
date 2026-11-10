package com.quantify.setting;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统配置。
 *
 * <p>注意 {@code classId} 用 <b>0 表示全局</b>，不要用 NULL——
 * MySQL 唯一索引允许多行 NULL，用 NULL 会产生重复的"全局"配置行，唯一键形同虚设。
 */
@Data
@TableName("sys_config")
public class SysConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 0 = 全局默认 */
    private Long classId;

    private String configKey;
    private String configValue;
    private String remark;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
