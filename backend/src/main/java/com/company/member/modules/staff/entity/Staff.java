package com.company.member.modules.staff.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 员工档案（表 m_staff）
 *
 * <p><b>员工与登录账号是两件事</b>（这是本表存在的全部理由）：
 * <ul>
 *   <li>员工是<b>业务主体</b>：业绩归他、工牌是他、提成算他。离职了历史订单的归属也不该变。</li>
 *   <li>账号是<b>访问凭证</b>：能登录、有角色、有数据权限。停用只是不能登录，不代表这个人不存在了。</li>
 * </ul>
 * 二者通过 {@code m_sys_user.staff_id} 建立「可选的一对一」关联：
 * 可以有员工没账号（新人还没开号），也可以有账号没员工（如纯运营/财务岗），
 * 但一个员工最多一个账号，一个账号最多挂一个员工。
 *
 * <p><b>离职为什么不删记录</b>：{@code m_order.staff_id} 指向本表。
 * 删掉员工会让历史订单的业绩归属变成悬空外键 —— 报表里那个人就凭空消失了。
 * 因此离职只置 {@code status=0} + 记 {@code leave_date}，
 * 真正需要清理时用逻辑删除（{@code deleted=1}），且仍有业绩的员工会被服务层拦住。
 */
@Data
@TableName("m_staff")
public class Staff implements Serializable {

    /** 离职 */
    public static final int ST_LEFT = 0;
    /** 在职 */
    public static final int ST_ACTIVE = 1;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工号（唯一），如 E001 */
    private String staffNo;
    private String staffName;
    private String phone;
    /** 所属门店 */
    private Long storeId;
    /** 岗位：店长/收银员/导购/库管… */
    private String position;
    private LocalDate entryDate;
    private LocalDate leaveDate;
    /** 状态：0 离职 1 在职 */
    private Integer status;

    private Long tenantId;
    private Integer deleted;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public boolean isActive() {
        return status != null && status == ST_ACTIVE;
    }

    public static String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        return status == ST_ACTIVE ? "在职" : "离职";
    }
}
