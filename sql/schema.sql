-- ============================================================================
--  量化积分管理系统 — 数据库设计 v0.8
--  MySQL 8.0 / utf8mb4
--
--  已在 MySQL 8.0.46 上完整执行通过（建表 + 初始化数据 + 全部示例查询，零报错）
--
--  规模：3 个年级 × 十几个班 × ~95 人 ≈ 4000 名学生，约 42 个班，约 380 个账号
--
--  ── 权限模型（v0.4：只定义"能做什么"，不绑定具体部门）──────────────────
--    级别   角色     能做什么                                数据范围
--    一级   ADMIN    全能：录入 + 审核 + 查看 + 导出 + 开户    全院
--    二级   EDITOR   录入 + 提交申请 + 查看；只能改自己提交的   全院
--    三级   VIEWER   仅查看                                  本班
--
--    一级 = 二级的全部能力 + 审核 + 开户 + 配置，是超级角色。
--    一级录入/变更时可选「直接生效」跳过审批；
--
--    权限只由「角色 role + 数据范围 scope」决定，与部门无关。
--    部门降级为一个可选标签（记录"这条分是哪个部门录的"），不参与鉴权判定。
--    账号数量不预设，由管理员按需自行创建。
--
--  ── 三条设计主线 ────────────────────────────────────────────────────
--    [流水] 每条加减分是独立记录，带事由/日期/状态，总分聚合得出，不手工填
--    [审批] 草稿 -> 待审 -> 通过/驳回；已通过的记录要改，只能再发一条变更申请
--    [范围] 角色决定"能做什么"，scope 决定"能看多大"，所有查询强制走 scope
-- ============================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `quantify`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_general_ci;

USE `quantify`;


-- ============================================================================
-- A. 组织标签：部门
--    注意：部门【不参与权限判定】。谁能干什么只由 sys_user.role 决定。
--    这张表的作用只是给记录打一个"来源"标签，方便日后追溯和统计。
--    如果以后不需要这个维度，整张表可以删掉，不影响任何权限逻辑。
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. 部门
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_dept`;
CREATE TABLE `sys_dept` (
  `id`         BIGINT      NOT NULL AUTO_INCREMENT,
  `dept_name`  VARCHAR(50) NOT NULL COMMENT '部门名，如 体育部',
  `full_name`  VARCHAR(100) NULL    COMMENT '全称，如 院学生会体育部',
  `sort_no`    INT         NOT NULL DEFAULT 0,
  `status`     TINYINT     NOT NULL DEFAULT 1 COMMENT '1启用 0停用（撤销的部门停用不删，历史流水还要显示）',
  `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dept_name` (`dept_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='部门（可选标签，不参与鉴权）';


-- ============================================================================
-- B. 数据组织线：年级 -> 班级 -> 学生
--
-- 只建到"年级"这一层，没有再建"学院"表：
-- 现在只有一个学院（软件工程学院），学院名放 sys_config。
-- 将来真要分学院，加一列 college_id 即可。
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 2. 年级
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_grade`;
CREATE TABLE `sys_grade` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT,
  `grade_name`  VARCHAR(20) NOT NULL COMMENT '年级名，如 25级',
  `enroll_year` INT         NOT NULL COMMENT '入学年份，如 2025，用于排序',
  `status`      TINYINT     NOT NULL DEFAULT 1 COMMENT '1启用 0停用（毕业后停用，不删）',
  `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_grade_name` (`grade_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='年级';


-- ----------------------------------------------------------------------------
-- 3. 班级
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_class`;
CREATE TABLE `sys_class` (
  `id`         BIGINT      NOT NULL AUTO_INCREMENT,
  `grade_id`   BIGINT      NOT NULL COMMENT '所属年级',
  `class_no`   INT         NOT NULL COMMENT '班号，如 10',
  `name`       VARCHAR(50) NOT NULL COMMENT '班级全称，如 25级10班',
  `status`     TINYINT     NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_grade_class_no` (`grade_id`, `class_no`),
  KEY `idx_grade` (`grade_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='班级';


-- ============================================================================
-- C. 账号与业务
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 4. 账号
--
--   权限只由两个字段决定，与部门无关：
--     role  决定"能做什么"：ADMIN 审核 / EDITOR 录入 / VIEWER 只读
--     scope 决定"能看多大"：1 全院 / 2 本年级 / 3 本班
--
--   建账号时由管理员自由组合，系统不预设岗位：
--     ADMIN  + 全院    → 能审全院、开户、导出、配置
--     EDITOR + 全院    → 能录全院、提交申请、只能改自己提交的
--     EDITOR + 本班    → 同上，但只能录本班学生
--     VIEWER + 本班    → 只读本班
--   账号数量不限，需要几个建几个。
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `username`      VARCHAR(50)  NOT NULL COMMENT '登录名，全局唯一',
  `password`      VARCHAR(100) NOT NULL COMMENT 'BCrypt 密文，永远不存明文/MD5',
  `real_name`     VARCHAR(50)  NOT NULL COMMENT '真实姓名',
  `role`          VARCHAR(20)  NOT NULL COMMENT 'ADMIN=一级审核员 / EDITOR=二级录入员 / VIEWER=三级只读',
  `dept_id`       BIGINT       NULL     COMMENT '可选标签，仅标注来源；不参与权限判定，可填 NULL',
  `scope_type`    TINYINT      NOT NULL COMMENT '1=全院 2=本年级 3=本班',
  `scope_id`      BIGINT       NOT NULL COMMENT 'scope_type=1 填 0；=2 填 grade_id；=3 填 class_id',
  `student_id`    BIGINT       NULL     COMMENT '本人是学生时关联 student.id；非学生填 NULL',
  `remark`        VARCHAR(200) NULL     COMMENT '备注，如"班长"、"体育部干事"，仅用于人工识别',
  `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 0禁用',
  `pwd_changed`   TINYINT      NOT NULL DEFAULT 0 COMMENT '0=初始密码未改，强制首次登录后修改',
  `last_login_at` DATETIME     NULL,
  `created_by`    BIGINT       NULL COMMENT '开户人',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  KEY `idx_role_status` (`role`, `status`),
  KEY `idx_scope` (`scope_type`, `scope_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号';


-- ----------------------------------------------------------------------------
-- 5. 学生（约 4000 行）
--    离班/退学不物理删除（status=0），否则历史积分记录会跟着烂掉
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `student`;
CREATE TABLE `student` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `class_id`   BIGINT       NOT NULL COMMENT '所属班级',
  `student_no` VARCHAR(20)  NOT NULL COMMENT '学号，如 25560641001',
  `name`       VARCHAR(50)  NOT NULL COMMENT '姓名',
  `base_score` DECIMAL(6,2) NOT NULL DEFAULT 50.00 COMMENT '基础分，默认 50',
  `status`     TINYINT      NOT NULL DEFAULT 1 COMMENT '1在册 0离班',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_class_student_no` (`class_id`, `student_no`),
  KEY `idx_class_status` (`class_id`, `status`),
  KEY `idx_name` (`name`) COMMENT '姓名模糊搜索（录入时选人）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学生';


-- ----------------------------------------------------------------------------
-- 6. score_item — 积分流水 / 申请单（★ 整个系统的核心表）
--
--    一条记录 = 一个事件 = 一张申请单
--    二级录入 -> 提交 -> 一级审批 -> 通过后才计入总分
--
--    容量估算：4000 人 × 约 15 条/学期 × 8 学期 ≈ 50 万行，含变更约 70 万行
--    InnoDB 在这个量级完全无压力，不需要分库分表，也不需要 Redis
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `score_item`;
CREATE TABLE `score_item` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `class_id`      BIGINT       NOT NULL COMMENT '冗余班级字段，配合 scope 的 IN 过滤，避免每次 join',
  `dept_id`       BIGINT       NULL     COMMENT '可选标签，记录"这条分是哪个部门录的"；随记录固化，可空',
  `student_id`    BIGINT       NOT NULL COMMENT '被加分/扣分的学生',
  `term`          VARCHAR(20)  NOT NULL COMMENT '学期，如 2025-2026-1',
  `category`      TINYINT      NOT NULL COMMENT '1加分 2扣分（职位分已取消）',
  `dimension`     TINYINT      NULL     COMMENT '五育类别：1德 2智 3体 4美 5劳；NULL=历史数据未分类',
  `points`        DECIMAL(6,2) NOT NULL COMMENT '分值，恒为正数，方向由 category 决定（避免 -(-2) 这类符号 bug）',
  `reason`        VARCHAR(500) NOT NULL COMMENT '事由，如"双语未到"',
  `occurred_on`   DATE         NULL     COMMENT '发生日期',
  `evidence_url`  VARCHAR(500) NULL     COMMENT '佐证材料路径（图片/PDF），可空',

  -- 审批状态机
  `status`        TINYINT      NOT NULL DEFAULT 0
      COMMENT '0草稿 1待审 2已通过 3已驳回 4已撤销 5已失效',
  -- 注：status=2 且 reviewed_by IS NULL 表示「一级直接录入生效」，没人审过；
  --     status=2 且 reviewed_by 有值 表示正常走完审批。用这个区分，不用加字段
  `batch_no`      VARCHAR(40)  NULL COMMENT '批量录入批次号：一次给多个学生录同一条时共用，便于整批撤回',
  `submitted_by`  BIGINT       NULL COMMENT '提交人',
  `submitted_at`  DATETIME     NULL,
  `reviewed_by`   BIGINT       NULL COMMENT '审核人（一级）',
  `reviewed_at`   DATETIME     NULL,
  `reject_reason` VARCHAR(500) NULL COMMENT '驳回理由，驳回时必填',

  -- 变更机制：已通过的记录不能直接改，只能新建一条变更申请指向它
  `origin_id`     BIGINT       NULL COMMENT '变更申请：指向被修改/被删除的原记录 id',
  `change_type`   TINYINT      NOT NULL DEFAULT 0 COMMENT '0新增 1修改 2删除',
  `superseded_by` BIGINT       NULL COMMENT '本记录被哪条新记录替代（status=5 时才有值）',

  `created_by`    BIGINT       NOT NULL COMMENT '录入人（二级）',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  KEY `idx_class_term_status`   (`class_id`, `term`, `status`, `deleted`) COMMENT '列表/导出主查询',
  KEY `idx_student_term_status` (`student_id`, `term`, `status`)           COMMENT '单个学生明细',
  KEY `idx_pending`             (`status`, `submitted_at`)                 COMMENT '待审列表（全院范围）',
  KEY `idx_dept_status`         (`dept_id`, `status`, `submitted_at`)      COMMENT '按部门筛选',
  KEY `idx_created_by`          (`created_by`, `status`)                   COMMENT '我的申请；二级只能改自己的',
  KEY `idx_batch`               (`batch_no`)                               COMMENT '按批次整批撤回',
  KEY `idx_dimension`           (`dimension`)                              COMMENT '五育类别筛选/统计',
  KEY `idx_origin`              (`origin_id`),
  CONSTRAINT `fk_item_student` FOREIGN KEY (`student_id`) REFERENCES `student` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='积分流水/申请单';


-- ----------------------------------------------------------------------------
-- 7. audit_log — 操作日志
--    评分类系统最容易起争议，谁在什么时候把什么从多少改成了多少，必须留痕。
--    批量审批也会在这里留一条 BATCH_APPROVE，写清楚一次性通过了哪些 id
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `audit_log`;
CREATE TABLE `audit_log` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT,
  `class_id`    BIGINT      NOT NULL DEFAULT 0 COMMENT '0=跨班级的批量操作',
  `dept_id`     BIGINT      NULL     COMMENT '操作人所属部门，冗余存一份便于按部门查',
  `target_type` VARCHAR(30) NOT NULL COMMENT 'SCORE_ITEM / SCORE_BATCH / SCORE_APPEAL / STUDENT / CLASS / USER / CONFIG',
  `target_id`   BIGINT      NOT NULL DEFAULT 0 COMMENT '单个操作填 id；批量操作填 0，明细看 after_json',
  `action`      VARCHAR(30) NOT NULL
      COMMENT 'CREATE/SUBMIT/APPROVE/REJECT/REVOKE/EXPORT/LOGIN/LOGIN_FAIL/BATCH_APPROVE/BATCH_IMPORT',
  `before_json` JSON        NULL COMMENT '改动前快照',
  `after_json`  JSON        NULL COMMENT '改动后快照',
  `operator_id` BIGINT      NOT NULL,
  `operator_ip` VARCHAR(45) NULL,
  `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_target`     (`target_type`, `target_id`),
  KEY `idx_class_time` (`class_id`, `created_at`),
  KEY `idx_dept_time`  (`dept_id`, `created_at`),
  KEY `idx_operator`   (`operator_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志';


-- ----------------------------------------------------------------------------
-- 8. sys_config — 配置
--    注意 class_id 用 0 表示全局默认，不要用 NULL：
--    MySQL 唯一索引允许多个 NULL，用 NULL 会产生重复的"全局"配置行
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_config`;
CREATE TABLE `sys_config` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `class_id`     BIGINT       NOT NULL DEFAULT 0 COMMENT '0=全局默认',
  `config_key`   VARCHAR(50)  NOT NULL,
  `config_value` VARCHAR(200) NOT NULL,
  `remark`       VARCHAR(200) NULL,
  `updated_by`   BIGINT       NULL,
  `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_class_key` (`class_id`, `config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统配置';


-- ----------------------------------------------------------------------------
-- 9. score_appeal — 量化申诉
--
--    只有三级（VIEWER）能发起，且只能对「已通过」的记录申诉。
--    一级受理时必须走变更链（新建变更记录 + 原记录置为已失效），不能直接改原记录。
--
--    active_flag 是生成列：status=0 时为 1，否则为 NULL。
--    配合唯一索引保证「同一学生对同一条记录、同一申诉人」只有一条待处理申诉；
--    已处理完的历史申诉 active_flag 为 NULL，不受唯一索引限制。
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `score_appeal`;
CREATE TABLE `score_appeal` (
  `id`               BIGINT       NOT NULL AUTO_INCREMENT,
  `score_item_id`    BIGINT       NOT NULL COMMENT '被申诉的流水',
  `student_id`       BIGINT       NOT NULL,
  `class_id`         BIGINT       NOT NULL COMMENT '冗余，配合范围过滤',
  `term`             VARCHAR(20)  NOT NULL,
  `appeal_type`      TINYINT      NOT NULL DEFAULT 4 COMMENT '1分数有误 2事由有误 3重复录入 4其他',
  `appeal_reason`    VARCHAR(500) NOT NULL,
  `evidence_url`     VARCHAR(500) NULL,
  `status`           TINYINT      NOT NULL DEFAULT 0 COMMENT '0待处理 1已受理 2已驳回 3已撤回',
  `result_change_id` BIGINT       NULL COMMENT '受理后生成的变更记录 id',
  `notify_status`    TINYINT      NOT NULL DEFAULT 0 COMMENT '0未发 1成功 2失败',
  `notify_error`     VARCHAR(500) NULL,
  `reviewed_by`      BIGINT       NULL,
  `reviewed_at`      DATETIME     NULL,
  `review_remark`    VARCHAR(500) NULL,
  `created_by`       BIGINT       NOT NULL COMMENT '申诉人（三级）',
  `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`          TINYINT      NOT NULL DEFAULT 0,
  `active_flag`      TINYINT GENERATED ALWAYS AS (IF(`status` = 0, 1, NULL)) STORED,
  PRIMARY KEY (`id`),
  KEY `idx_score_item` (`score_item_id`),
  KEY `idx_status_created` (`status`, `created_at`),
  KEY `idx_class_status` (`class_id`, `status`),
  KEY `idx_created_by` (`created_by`, `status`),
  UNIQUE KEY `uk_active_appeal` (`score_item_id`, `created_by`, `active_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='量化申诉';


-- ============================================================================
-- 初始化数据
-- ============================================================================

INSERT INTO `sys_config` (`class_id`, `config_key`, `config_value`, `remark`) VALUES
  (0, 'college_name',       '软件工程学院', '导出表头用'),
  (0, 'current_term',       '2026-2027-1', '当前学期，录入和导出默认用这个值'),
  (0, 'default_base_score', '50',          '新生默认基础分'),
  (0, 'auto_approve_limit', '0',           '分值<=该值二级自审即生效；0 表示全部要一级审批'),
  (0, 'quantify_ratio',     '',            '量化成绩折算分母，留空则不导出该列（规则未定，先不用）');

-- 部门（可选标签，纯粹为了让记录能标出来源，不影响任何权限判定）
-- 需要就在后台加，不需要就整张表空着，系统照跑
INSERT INTO `sys_dept` (`id`, `dept_name`, `full_name`, `sort_no`) VALUES
  (1, '权益部',   '院学生会学生权益委员会', 1),
  (2, '体育部',   '院学生会体育部',         2),
  (3, '学习部',   '院学生会学习部',         3),
  (4, '心理部',   '院学生会心理部',         4),
  (5, '融媒体部', '院学生会融媒体中心',     5),
  (6, '志愿服务部','院学生会志愿服务部',    6),
  (7, '勤工部',   '院学生会勤工部',         7),
  (8, '学工办',   '院学生会学工办',         8);

INSERT INTO `sys_grade` (`id`, `grade_name`, `enroll_year`) VALUES
  (1, '25级', 2025),
  (2, '24级', 2024),
  (3, '23级', 2023);

-- ⚠ 班级清单需要真实数据（3 个年级各十几个班），先只建已知的这一个
INSERT INTO `sys_class` (`id`, `grade_id`, `class_no`, `name`) VALUES
  (1, 1, 10, '25级10班');

-- 账号由管理员在后台自行创建，不写死在 SQL 里（密码需要 BCrypt 加密）。
-- 系统不预设岗位，建账号时自由组合「角色 + 数据范围」：
--   ADMIN  + 全院   审核员（能审、能导出、能开户）
--   EDITOR + 全院   录入员（能录全院、只能改自己提交的）
--   EDITOR + 本班   录入员（范围收窄到本班）
--   VIEWER + 本班   查看员（只读本班）
-- 数量不限，需要几个建几个。
-- 初始化时会创建几个示例账号（见 DataInitializer），首登强制改密。


-- ============================================================================
-- 常用查询
--
-- 下面每一条都可以直接复制到 mysql 客户端里执行，不需要替换任何占位符。
-- （Q6 / Q7 是写代码时的参考，用注释包起来了，因为里面是伪代码）
-- ============================================================================

-- ---------------------------------------------------------------------------
-- Q0. 数据范围解析：把登录账号的 scope 解析成一组 class_id
--     结果缓存起来（42 个班最多 42 个 id，本地 Caffeine 缓存即可，不用 Redis）
--     之后所有业务查询统一用 class_id IN (...) 过滤，不要写三套分支
-- ---------------------------------------------------------------------------
-- 全院（权益部/各部门）：SELECT id FROM sys_class WHERE status = 1
-- 本年级：              SELECT id FROM sys_class WHERE grade_id = #{scopeId} AND status = 1
-- 本班（班委）：         SELECT id FROM sys_class WHERE id = #{scopeId}


-- ---------------------------------------------------------------------------
-- Q1. 总分汇总（不落表，实时聚合）
--     基础分 + Σ已通过的(加分 - 扣分)
-- ---------------------------------------------------------------------------
SELECT
  s.id,
  s.student_no,
  s.name,
  s.base_score,
  s.base_score
    + IFNULL(SUM(CASE WHEN i.category = 2 THEN -i.points ELSE i.points END), 0) AS total
FROM `student` s
LEFT JOIN `score_item` i
       ON i.student_id = s.id
      AND i.term    = '2025-2026-1'
      AND i.status  = 2          -- 只算已通过
      AND i.deleted = 0
WHERE s.class_id IN (1)          -- ← 数据范围过滤
  AND s.status   = 1
GROUP BY s.id, s.student_no, s.name, s.base_score
ORDER BY total DESC;


-- ---------------------------------------------------------------------------
-- Q2. Excel 导出（列顺序对齐原《25级10班积分册.xlsx》）
--
--     ⚠ 两条硬规则：
--       1) 导出和页面必须走同一条聚合逻辑，否则会出现"页面和导出的表对不上"
--       2) GROUP_CONCAT 默认上限 1024 字节，超了静默截断，必须先放开
--
--     ⚠ 4000 人的范围不要指望一条 SQL 出结果：
--       一个班一个 sheet，按班循环调用下面这条（每班 ~95 行，安全）
--       42 个班 × 一条 SQL，几秒钟的事，比一条巨型 SQL 稳得多
-- ---------------------------------------------------------------------------
SET SESSION group_concat_max_len = 1048576;

SELECT
  ROW_NUMBER() OVER (ORDER BY s.student_no)                    AS `序号`,
  s.student_no                                                 AS `学号`,
  s.name                                                       AS `姓名`,

  IFNULL(SUM(CASE WHEN i.category = 1 THEN i.points END), 0)   AS `加分`,
  IFNULL(GROUP_CONCAT(
    CASE WHEN i.category = 1 THEN CONCAT(
      i.seq, '.', i.reason, '(+',
      TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(i.points AS CHAR))), ')',
      IF(i.occurred_on IS NULL, '', CONCAT('(', DATE_FORMAT(i.occurred_on, '%m.%d'), ')'))
    ) END
    ORDER BY i.seq SEPARATOR ''), '')                          AS `加分备注`,

  -- 原表扣分列存的是负数，这里取负号保持一致
  -IFNULL(SUM(CASE WHEN i.category = 2 THEN i.points END), 0)  AS `扣分`,
  IFNULL(GROUP_CONCAT(
    CASE WHEN i.category = 2 THEN CONCAT(
      i.seq, '.', i.reason, '(-',
      TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(i.points AS CHAR))), ')',
      IF(i.occurred_on IS NULL, '', CONCAT('(', DATE_FORMAT(i.occurred_on, '%m.%d'), ')'))
    ) END
    ORDER BY i.seq SEPARATOR ''), '')                          AS `扣分备注`,

  s.base_score                                                 AS `基础分`,
  s.base_score
    + IFNULL(SUM(CASE WHEN i.category = 2 THEN -i.points ELSE i.points END), 0) AS `总分`
FROM `student` s
LEFT JOIN (
    -- 先给每个学生的每类流水编号（1. 2. 3. …），还原原表备注的写法
    SELECT id, student_id, category, points, reason, occurred_on,
           ROW_NUMBER() OVER (PARTITION BY student_id, category
                              ORDER BY occurred_on, id) AS seq
    FROM `score_item`
    WHERE term    = '2025-2026-1'
      AND status  = 2
      AND deleted = 0
) i ON i.student_id = s.id
WHERE s.class_id = 1
  AND s.status   = 1
GROUP BY s.id, s.student_no, s.name, s.base_score
ORDER BY s.student_no;


-- ---------------------------------------------------------------------------
-- Q3. 待审列表（一级）
--     全院范围下一次性可能有几百条，必须分页 + 支持批量通过
--
--     下面的 @deptId 演示"可选筛选"的写法：
--       传 NULL  → 不过滤部门，看全部（默认）
--       传具体值 → 只看该部门提交的
--     在 Java 里对应 MyBatis 的
--       <if test="deptId != null"> AND i.dept_id = #{deptId} </if>
-- ---------------------------------------------------------------------------
SET @deptId = NULL;   -- 想按部门筛选就把这里改成部门 id，例如 2

SELECT
  i.id, i.class_id, i.dept_id, d.dept_name, i.category, i.points, i.reason, i.occurred_on,
  st.name AS student_name, st.student_no, c.name AS class_name,
  u.real_name AS submitter, i.submitted_at, i.change_type, i.origin_id
FROM `score_item` i
JOIN `student`  st ON st.id = i.student_id
JOIN `sys_class` c ON c.id  = i.class_id
JOIN `sys_user` u  ON u.id  = i.submitted_by
LEFT JOIN `sys_dept` d ON d.id = i.dept_id
WHERE i.status   = 1
  AND i.deleted  = 0
  AND (@deptId IS NULL OR i.dept_id = @deptId)
ORDER BY i.submitted_at DESC
LIMIT 0, 50;


-- ---------------------------------------------------------------------------
-- Q4. 某个学生的全部明细（学生自查 / 班委查看）
-- ---------------------------------------------------------------------------
SELECT i.id, i.category, i.points, i.reason, i.occurred_on, i.status,
       d.dept_name, i.created_at
FROM `score_item` i
LEFT JOIN `sys_dept` d ON d.id = i.dept_id
WHERE i.student_id = 1
  AND i.term       = '2025-2026-1'
  AND i.deleted    = 0
ORDER BY i.occurred_on DESC, i.id DESC;


-- ---------------------------------------------------------------------------
-- Q5. 我的申请（二级）：只能看到、也只能改自己提交的
--     在 Java 里 created_by 取 ScopeContext.getUserId()，这里写死 1 做演示
-- ---------------------------------------------------------------------------
SELECT id, student_id, category, points, reason, status, reject_reason, submitted_at
FROM `score_item`
WHERE created_by = 1
  AND deleted    = 0
ORDER BY id DESC
LIMIT 0, 20;


-- ---------------------------------------------------------------------------
-- Q6. 变更生效：权益部通过一条"修改申请"时，在一个事务里做两件事
--     下面的 SQL 要包在同一个 @Transactional 中执行
-- ---------------------------------------------------------------------------
-- UPDATE score_item SET status = 5, superseded_by = #{newId} WHERE id = #{originId};
-- UPDATE score_item SET status = 2, reviewed_by = #{adminId}, reviewed_at = NOW()
--   WHERE id = #{newId};


-- ---------------------------------------------------------------------------
-- Q7. 批量录入：给多个学生录同一条（如"元旦晚会志愿者 +3"）
--     批次号相同，出问题可以整批撤回，不用一条条找
-- ---------------------------------------------------------------------------
-- SELECT batch_no, COUNT(*) FROM score_item
--  WHERE batch_no = '20251221-xxx' GROUP BY batch_no;
-- UPDATE score_item SET status = 4 WHERE batch_no = '20251221-xxx' AND status = 1;
