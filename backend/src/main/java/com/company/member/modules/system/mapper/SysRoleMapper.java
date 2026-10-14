package com.company.member.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.member.modules.system.entity.SysRole;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {

    @Select("SELECT COUNT(1) FROM m_sys_role_menu WHERE role_id = #{roleId}")
    int countMenus(@Param("roleId") Long roleId);

    @Select("SELECT menu_id FROM m_sys_role_menu WHERE role_id = #{roleId}")
    List<Long> selectMenuIds(@Param("roleId") Long roleId);

    @Delete("DELETE FROM m_sys_role_menu WHERE role_id = #{roleId}")
    int deleteRoleMenus(@Param("roleId") Long roleId);

    @Insert("INSERT INTO m_sys_role_menu (role_id, menu_id, created_at) VALUES (#{roleId}, #{menuId}, NOW())")
    int insertRoleMenu(@Param("roleId") Long roleId, @Param("menuId") Long menuId);

    /** 该角色下的用户 id（用于权限变更后清理缓存） */
    @Select("SELECT user_id FROM m_sys_user_role WHERE role_id = #{roleId}")
    List<Long> selectUserIdsByRole(@Param("roleId") Long roleId);
}
