package com.company.member.modules.cashier.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单明细（表 m_order_item）
 *
 * <p>商品名/价格/折扣参与标记均为**下单时快照**，后续改价不影响历史订单。
 */
@Data
@TableName("m_order_item")
public class OrderItem implements Serializable {

    public static final int TYPE_PRODUCT = 1;
    public static final int TYPE_SERVICE = 2;
    public static final int TYPE_CARD = 3;
    public static final int TYPE_FREE_AMOUNT = 4;
    public static final int TYPE_EXCHANGE_GOODS = 5;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;
    private String orderNo;
    private Integer itemType;
    private Long productId;
    private String productName;
    private String productCode;
    private String spec;
    private BigDecimal price;
    private BigDecimal qty;
    private BigDecimal totalAmount;
    private BigDecimal discountAmount;
    private BigDecimal actualAmount;
    private Integer joinDiscount;
    private Integer joinPoints;
    private BigDecimal pointsRate;
    private Long cardId;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
