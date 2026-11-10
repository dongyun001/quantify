package com.quantify.score;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 积分流水 Mapper。
 *
 * <p>聚合和导出这类复杂 SQL 放 XML，见 {@code resources/mapper/ScoreItemMapper.xml}。
 * 页面和导出<b>必须走同一套聚合逻辑</b>，否则会出现"页面 8 分、导出 11 分"。
 */
@Mapper
public interface ScoreItemMapper extends BaseMapper<ScoreItem> {

    /**
     * 积分总表（含排名）。
     *
     * @param classIds 数据范围，为空表示不限（一级/二级都是全院，三级是本班）
     * @param term     学期
     * @param keyword  学号或姓名模糊搜索，可空
     * @param classId  按班级筛选，可空
     * @param gradeId  按年级筛选，可空
     */
    List<ScoreBoardVO> selectBoard(@Param("classIds") Collection<Long> classIds,
                                   @Param("term") String term,
                                   @Param("keyword") String keyword,
                                   @Param("classId") Long classId,
                                   @Param("gradeId") Long gradeId,
                                   @Param("offset") long offset,
                                   @Param("limit") long limit);

    /** 总表总数。筛选条件与 selectBoard 完全一致（共用 boardWhere） */
    long countBoard(@Param("classIds") Collection<Long> classIds,
                    @Param("term") String term,
                    @Param("keyword") String keyword,
                    @Param("classId") Long classId,
                    @Param("gradeId") Long gradeId);

    /** 单个学生的分数汇总，字段结构与总表一致（不带排名） */
    ScoreBoardVO selectStudentSummary(@Param("studentId") Long studentId,
                                      @Param("term") String term);

    /** 待审列表（一级用），按提交时间倒序 */
    List<ScoreItem> selectPending(@Param("deptId") Long deptId,
                                  @Param("classId") Long classId,
                                  @Param("category") Integer category,
                                  @Param("dimension") Integer dimension,
                                  @Param("changeOnly") Boolean changeOnly,
                                  @Param("offset") long offset,
                                  @Param("limit") long limit);

    long countPending(@Param("deptId") Long deptId,
                      @Param("classId") Long classId,
                      @Param("category") Integer category,
                      @Param("dimension") Integer dimension,
                      @Param("changeOnly") Boolean changeOnly);

    /** 流水明细（带学生、班级、来源、提交人等展示字段） */
    List<ScoreItem> selectItems(@Param("studentId") Long studentId,
                                @Param("term") String term,
                                @Param("category") Integer category,
                                @Param("dimension") Integer dimension,
                                @Param("status") Integer status);

    /** 我的申请 */
    List<ScoreItem> selectMine(@Param("userId") Long userId,
                               @Param("status") Integer status,
                               @Param("offset") long offset,
                               @Param("limit") long limit);

    long countMine(@Param("userId") Long userId, @Param("status") Integer status);

    /** 按 id 查单条（带展示字段） */
    ScoreItem selectItemById(@Param("id") Long id);

    /** 导出用：一个班的所有学生分数（备注列需要拼接，见 XML 里的说明） */
    List<ScoreBoardVO> selectForExport(@Param("classId") Long classId,
                                       @Param("term") String term);

    /** 二级改不了别人提交的——判断归属用 */
    int countOwnedBy(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 放开 GROUP_CONCAT 的长度上限。
     *
     * <p>默认 1024 字节，超了会【静默截断、不报错】，导出的备注会莫名少一截。
     * 必须和导出查询跑在同一条连接上，所以调用方要加 @Transactional 把连接钉住。
     */
    @org.apache.ibatis.annotations.Update("SET SESSION group_concat_max_len = 1048576")
    void enableLongGroupConcat();
}
