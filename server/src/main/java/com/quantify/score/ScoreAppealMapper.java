package com.quantify.score;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 申诉 Mapper。
 *
 * <p>列表和详情都要带学生、班级、原记录、申诉人、审核人，放在 XML 里；
 * 数据范围过滤统一写 {@code a.class_id IN (...)}，不在 Java 里分支（R7）。
 */
@Mapper
public interface ScoreAppealMapper extends BaseMapper<ScoreAppeal> {

    List<ScoreAppeal> selectMine(@Param("userId") Long userId,
                                 @Param("status") Integer status,
                                 @Param("offset") long offset,
                                 @Param("limit") long limit);

    long countMine(@Param("userId") Long userId, @Param("status") Integer status);

    List<ScoreAppeal> selectPending(@Param("classIds") Collection<Long> classIds,
                                    @Param("offset") long offset,
                                    @Param("limit") long limit);

    long countPending(@Param("classIds") Collection<Long> classIds);

    /** 带学生、班级、原记录、申诉人、审核人 */
    ScoreAppeal selectDetail(@Param("id") Long id);
}
