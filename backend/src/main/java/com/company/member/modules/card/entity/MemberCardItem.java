package com.company.member.modules.card.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 次卡权益项（表 m_member_card_item）
 *
 * <p>一张卡可含多个权益项（如"洗车 10 次 + 打蜡 2 次"），核销按项进行。
 */
@Data
@TableName("m_member_card_item")
public class MemberCardItem implements Serializable {

    public static final int STATUS_NORMAL = 1;
    public static final int STATUS_USED_UP = 2;
    public static final int STATUS_EXPIRED = 3;
    public static final int STATUS_FROZEN = 4;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long memberCardId;
    private Long memberId;
    private String itemName;
    private Long productId;
    private Integer totalTimes;
    private Integer usedTimes;
    private Integer remainTimes;
    private LocalDateTime validStart;
    private LocalDateTime validEnd;
    private Integer status;
    private Integer sortNo;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
