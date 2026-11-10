package com.quantify.org;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ClassMapper extends BaseMapper<SysClass> {

    /** 全院范围的 class_id 列表，供数据范围解析用 */
    @Select("SELECT id FROM sys_class WHERE status = 1")
    List<Long> selectAllEnabledIds();

    /** 本年级的 class_id 列表 */
    @Select("SELECT id FROM sys_class WHERE grade_id = #{gradeId} AND status = 1")
    List<Long> selectIdsByGrade(@Param("gradeId") Long gradeId);
}
