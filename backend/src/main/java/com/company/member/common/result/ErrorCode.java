package com.company.member.common.result;

import lombok.Getter;

/**
 * 统一错误码
 *
 * <p>编码规范：0 成功；1xxxx 通用；2xxxx 会员；3xxxx 资产；4xxxx 订单；
 * 5xxxx 营销卡券；6xxxx 微信/外部；7xxxx 支付；9xxxx 系统
 */
@Getter
public enum ErrorCode {

    /* ========== 成功 ========== */
    SUCCESS(0, "success"),

    /* ========== 1xxxx 通用 ========== */
    PARAM_INVALID(10001, "参数校验失败"),
    UNAUTHORIZED(10002, "未登录或登录已过期"),
    FORBIDDEN(10003, "无权限执行该操作"),
    DATA_NOT_FOUND(10004, "数据不存在"),
    TOO_MANY_REQUESTS(10005, "请求过于频繁，请稍后再试"),
    REPEAT_SUBMIT(10006, "请勿重复提交"),
    OPERATION_FAILED(10007, "操作失败"),
    DATA_SCOPE_DENIED(10008, "超出你的数据权限范围"),
    EXPORT_TOO_LARGE(10009, "数据量过大，请缩小查询范围后导出"),
    FILE_TYPE_NOT_ALLOWED(10010, "文件类型不允许"),
    FILE_TOO_LARGE(10011, "文件过大"),

    /* ========== 2xxxx 会员 ========== */
    PHONE_EXISTS(20001, "该手机号已存在会员，请直接使用或走合并流程"),
    MEMBER_FROZEN(20002, "会员已被冻结，无法操作"),
    MEMBER_CANCELLED(20003, "会员已注销"),
    NEED_BIND_PHONE(20004, "请先绑定手机号"),
    MEMBER_NOT_FOUND(20005, "会员不存在"),
    PHONE_INVALID(20006, "手机号格式不正确"),
    MEMBER_BALANCE_NOT_ZERO(20007, "会员余额不为 0，不可注销"),
    MERGE_NOT_ALLOWED(20008, "该会员存在未完成业务，不可合并"),
    IMPORT_FILE_INVALID(20009, "导入文件格式不正确"),

    /* ========== 3xxxx 资产 ========== */
    BALANCE_NOT_ENOUGH(30001, "余额不足"),
    POINTS_NOT_ENOUGH(30002, "积分不足"),
    AMOUNT_INVALID(30003, "金额非法"),
    ACCOUNT_NOT_FOUND(30004, "资产账户不存在"),
    ACCOUNT_NOT_RECONCILED(30005, "余额与流水不一致，请先对账"),
    BALANCE_EXCEED_LIMIT(30006, "超出单会员余额上限"),
    CONCURRENT_MODIFY(30007, "数据已被其他操作修改，请重试"),
    GIFT_AMOUNT_EXCEED(30008, "赠送金额超出允许比例"),
    ADJUST_REASON_REQUIRED(30009, "资产调整必须填写原因"),
    RECHARGE_REVOKE_NOT_ALLOWED(30010, "该充值单已产生消费，不可撤销"),

    /* ========== 4xxxx 订单 ========== */
    ORDER_NOT_FOUND(40001, "订单不存在"),
    ORDER_ALREADY_PAID(40002, "订单已支付，请勿重复提交"),
    ORDER_STATUS_INVALID(40003, "订单状态不允许该操作"),
    REFUND_AMOUNT_EXCEED(40004, "退款金额超过可退金额"),
    HOLD_ORDER_NOT_FOUND(40005, "挂单不存在或已被取用"),
    PAYMENT_AMOUNT_MISMATCH(40006, "支付金额与应收金额不一致"),
    PRODUCT_OFF_SHELF(40007, "商品已下架"),
    ORDER_NEED_MEMBER(40008, "该订单必须选择会员"),
    REFUND_NEED_APPROVE(40009, "该退单需要审批"),

    /* ========== 5xxxx 营销卡券 ========== */
    COUPON_UNAVAILABLE(50001, "优惠券不可用"),
    COUPON_EXPIRED(50002, "优惠券已过期"),
    COUPON_THRESHOLD_NOT_MET(50003, "未满足优惠券使用门槛"),
    COUPON_SCOPE_MISMATCH(50004, "优惠券不适用于该门店或商品"),
    COUPON_USED(50005, "优惠券已被使用"),
    CARD_TIMES_NOT_ENOUGH(50006, "次卡剩余次数不足"),
    CARD_UNAVAILABLE(50007, "次卡已过期、冻结或用完"),
    COUPON_STACK_NOT_ALLOWED(50008, "所选优惠券不可叠加使用"),
    COUPON_LIMIT_EXCEEDED(50009, "已达该券每人限领数量"),
    COUPON_SOLD_OUT(50010, "优惠券已领完"),
    COUPON_LOCKED(50011, "优惠券正被其他订单占用"),

    /* ========== 6xxxx 微信/外部 ========== */
    WX_CODE_INVALID(60001, "微信登录凭证无效，请重试"),
    WX_PHONE_DECRYPT_FAIL(60002, "手机号获取失败，请重试"),
    WX_SUBSCRIBE_FAIL(60003, "订阅消息发送失败"),
    WX_REQUEST_TIMEOUT(60004, "请求微信接口超时"),

    /* ========== 7xxxx 支付（预留） ========== */
    PAY_CHANNEL_NOT_READY(70001, "支付渠道未启用或未配置完成"),
    PAYMENT_NOT_FOUND(70002, "支付单不存在"),
    PAYMENT_STATUS_INVALID(70003, "支付单状态不允许该操作"),
    PAY_AMOUNT_MISMATCH(70004, "支付金额与订单金额不一致"),
    PAYMENT_CLOSED(70005, "支付单已关闭，请重新下单"),
    PAY_DUPLICATE(70006, "该订单已支付"),
    PAY_NOTIFY_VERIFY_FAIL(70007, "支付回调验签失败"),
    PAY_CHANNEL_ERROR(70008, "支付渠道下单失败"),
    PAY_REFUND_ERROR(70009, "支付渠道退款失败"),
    PAY_REFUND_EXCEED(70010, "退款金额超过可退金额"),
    PAY_REFUND_UNSUPPORTED(70011, "该订单不支持在线退款"),
    PAY_NEED_BIND_PHONE(70012, "请先绑定手机号后再发起支付"),

    /* ========== 9xxxx 系统 ========== */
    SYSTEM_ERROR(90001, "系统繁忙，请稍后重试"),
    DB_ERROR(90002, "数据库操作异常"),
    IDEMPOTENT_CONFLICT(90003, "请求正在处理中，请勿重复提交"),
    CONFIG_MISSING(90004, "系统参数缺失"),
    LOGIN_FAILED(90005, "用户名或密码错误"),
    ACCOUNT_DISABLED(90006, "账号已被停用"),
    LOGIN_LOCKED(90007, "登录失败次数过多，请 15 分钟后再试"),
    OLD_PASSWORD_WRONG(90008, "原密码不正确"),
    ROLE_IN_USE(90009, "该角色已被用户使用，不可删除"),
    ROLE_CODE_EXISTS(90010, "角色编码已存在"),
    USERNAME_EXISTS(90011, "登录账号已存在"),
    DATA_IN_USE(90012, "数据已被引用，不可删除"),
    JOB_RUNNING(90013, "任务正在执行中"),
    ;

    private final int code;
    private final String msg;

    ErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
