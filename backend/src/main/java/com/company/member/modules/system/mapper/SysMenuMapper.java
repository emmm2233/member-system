package com.company.member.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.member.modules.system.entity.SysMenu;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysMenuMapper extends BaseMapper<SysMenu> {

    /** 全部启用的菜单（超管用） */
    @Select("""
            SELECT * FROM m_sys_menu
             WHERE status = 1 AND menu_type IN (1, 2)
             ORDER BY parent_id, sort_no, id
            """)
    List<SysMenu> selectAllVisible();

    /** 用户拥有的菜单（经角色） */
    @Select("""
            SELECT DISTINCT m.*
              FROM m_sys_user_role ur
              JOIN m_sys_role r        ON r.id = ur.role_id AND r.deleted = 0 AND r.status = 1
              JOIN m_sys_role_menu rm  ON rm.role_id = r.id
              JOIN m_sys_menu m        ON m.id = rm.menu_id AND m.status = 1
             WHERE ur.user_id = #{userId} AND m.menu_type IN (1, 2)
             ORDER BY m.parent_id, m.sort_no, m.id
            """)
    List<SysMenu> selectByUserId(@Param("userId") Long userId);
}
