package com.company.member.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.member.modules.system.entity.SysUser;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /** 用户拥有的角色编码 */
    @Select("""
            SELECT r.role_code
              FROM m_sys_user_role ur
              JOIN m_sys_role r ON r.id = ur.role_id AND r.deleted = 0 AND r.status = 1
             WHERE ur.user_id = #{userId}
            """)
    List<String> selectRoleCodes(@Param("userId") Long userId);

    /** 用户拥有的权限码（经角色 → 菜单） */
    @Select("""
            SELECT DISTINCT m.perm_code
              FROM m_sys_user_role ur
              JOIN m_sys_role r      ON r.id = ur.role_id AND r.deleted = 0 AND r.status = 1
              JOIN m_sys_role_menu rm ON rm.role_id = r.id
              JOIN m_sys_menu m      ON m.id = rm.menu_id AND m.status = 1
             WHERE ur.user_id = #{userId}
               AND m.perm_code IS NOT NULL
               AND m.perm_code <> ''
            """)
    List<String> selectPermCodes(@Param("userId") Long userId);

    /** 已分配该角色的用户数（删除角色前校验） */
    @Select("SELECT COUNT(1) FROM m_sys_user_role WHERE role_id = #{roleId}")
    int countByRoleId(@Param("roleId") Long roleId);

    @Update("UPDATE m_sys_user SET token_version = token_version + 1, updated_at = NOW() WHERE id = #{userId}")
    int bumpTokenVersion(@Param("userId") Long userId);

    @Update("""
            UPDATE m_sys_user SET last_login_at = #{loginAt}, last_login_ip = #{ip}, updated_at = NOW()
             WHERE id = #{userId}
            """)
    int updateLoginInfo(@Param("userId") Long userId, @Param("loginAt") java.time.LocalDateTime loginAt,
                        @Param("ip") String ip);

    /* ==================== 用户角色绑定（RBAC 管理用） ==================== */

    @Select("SELECT role_id FROM m_sys_user_role WHERE user_id = #{userId}")
    List<Long> selectRoleIds(@Param("userId") Long userId);

    @Delete("DELETE FROM m_sys_user_role WHERE user_id = #{userId}")
    int deleteUserRoles(@Param("userId") Long userId);

    @Insert("INSERT INTO m_sys_user_role (user_id, role_id, created_at) VALUES (#{userId}, #{roleId}, NOW())")
    int insertUserRole(@Param("userId") Long userId, @Param("roleId") Long roleId);

    /* ==================== 员工档案关联（员工管理用） ==================== */

    /**
     * 把账号关联到员工
     *
     * <p>条件里带上 {@code staff_id IS NULL}：并发下两个请求同时绑定同一个账号时，
     * 只有一个能改成功，另一个影响 0 行并拿到明确报错，
     * 不会出现「一个账号被两个员工抢走」的脏数据。
     */
    @Update("""
            UPDATE m_sys_user SET staff_id = #{staffId}, updated_at = NOW()
             WHERE id = #{userId} AND deleted = 0 AND (staff_id IS NULL OR staff_id = #{staffId})
            """)
    int bindStaff(@Param("userId") Long userId, @Param("staffId") Long staffId);

    /** 解绑员工（账号本身不受影响，只是不再代表某个员工） */
    @Update("UPDATE m_sys_user SET staff_id = NULL, updated_at = NOW() WHERE id = #{userId}")
    int clearStaffId(@Param("userId") Long userId);
}
