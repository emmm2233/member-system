package com.company.member.modules.system.entity;

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
 * 操作日志（表 m_op_log）
 *
 * <p><b>为什么这张表必须被写入</b>：会员余额/赠送的调整、退单审批、手工发券这些动作，
 * 一旦发生争议就要能回答「谁、什么时候、改了多少、为什么改」。
 * 只靠业务流水（`m_balance_flow`）反推是不够的 —— 流水里有 `operator_id`，
 * 但没有操作来源 IP、请求参数、凭证图片、耗时与失败原因。
 *
 * <p>本表由 {@code OpLogAspect} 在写接口执行后落库，字段设计对应真实排查场景：
 * <ul>
 *   <li>{@code traceId} —— 与日志系统串联，能捞出这一次请求的完整调用链</li>
 *   <li>{@code beforeValue / afterValue} —— 争议时直接看「改前改后」</li>
 *   <li>{@code amount} —— 便于筛「大额操作」（如 >5000 的调整单独复核）</li>
 *   <li>{@code requestParam} —— 脱敏后的原始入参，还原不了业务时可据此重放</li>
 *   <li>{@code status / errorMsg} —— 失败的操作同样要留痕（谁尝试过什么）</li>
 * </ul>
 */
@Data
@TableName("m_op_log")
public class OpLog implements Serializable {

    /** 结果：失败 */
    public static final int ST_FAIL = 0;
    /** 结果：成功 */
    public static final int ST_OK = 1;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String traceId;
    /** 操作人（系统用户 id） */
    private Long userId;
    private String username;
    private Long storeId;

    /** 模块：member / account / order / card / marketing / system … */
    private String module;
    /** 操作类型：CREATE / UPDATE / DELETE / RECHARGE / CONSUME / REFUND / ADJUST / EXPORT / AUDIT */
    private String opType;
    private String opDesc;

    private String bizType;
    private Long bizId;
    private String bizNo;

    private String beforeValue;
    private String afterValue;
    /** 涉及金额，便于筛大额操作 */
    private BigDecimal amount;
    /** 操作原因（调整类必填） */
    private String reason;

    private String requestUri;
    private String requestMethod;
    /** 脱敏并截断后的请求参数 */
    private String requestParam;

    private String ip;
    private String userAgent;

    private Integer status;
    private String errorMsg;
    private Integer costMs;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
