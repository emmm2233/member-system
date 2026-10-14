package com.company.member.common.util;

import com.company.member.common.exception.BizException;
import com.company.member.common.result.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额工具：统一两位小数、统一舍入规则
 *
 * <p>铁律：金额只用 BigDecimal，禁止 double/float；比较用 compareTo。
 */
public final class MoneyUtil {

    /** 金额小数位 */
    public static final int SCALE = 2;
    /** 金额舍入方式：HALF_UP（银行家舍入不适用于零售场景） */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    public static final BigDecimal ZERO = scale(BigDecimal.ZERO);

    private MoneyUtil() {
    }

    public static BigDecimal scale(BigDecimal v) {
        return v == null ? null : v.setScale(SCALE, ROUNDING);
    }

    public static BigDecimal of(String v) {
        return scale(new BigDecimal(v));
    }

    public static BigDecimal of(double v) {
        return scale(BigDecimal.valueOf(v));
    }

    public static BigDecimal nullToZero(BigDecimal v) {
        return v == null ? ZERO : scale(v);
    }

    /** 是否为非负金额 */
    public static boolean isNonNegative(BigDecimal v) {
        return v != null && v.compareTo(BigDecimal.ZERO) >= 0;
    }

    /** 是否为正金额（> 0） */
    public static boolean isPositive(BigDecimal v) {
        return v != null && v.compareTo(BigDecimal.ZERO) > 0;
    }

    /** 校验必须为正金额，否则抛业务异常 */
    public static void requirePositive(BigDecimal v, String fieldName) {
        if (!isPositive(v)) {
            throw new BizException(ErrorCode.AMOUNT_INVALID, fieldName + "必须大于 0");
        }
    }

    /** 校验必须为非负金额 */
    public static void requireNonNegative(BigDecimal v, String fieldName) {
        if (v == null || v.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException(ErrorCode.AMOUNT_INVALID, fieldName + "不能为负数");
        }
    }

    /** 相加（自动处理 null） */
    public static BigDecimal add(BigDecimal a, BigDecimal b) {
        return scale(nullToZero(a).add(nullToZero(b)));
    }

    /** 相减 */
    public static BigDecimal sub(BigDecimal a, BigDecimal b) {
        return scale(nullToZero(a).subtract(nullToZero(b)));
    }

    /** 相乘并按金额舍入 */
    public static BigDecimal mul(BigDecimal a, BigDecimal b) {
        return scale(nullToZero(a).multiply(nullToZero(b)));
    }

    /** 相乘但保留更多小数位（用于单价×数量、比率计算） */
    public static BigDecimal mul(BigDecimal a, BigDecimal b, int scale) {
        return nullToZero(a).multiply(nullToZero(b)).setScale(scale, ROUNDING);
    }

    /** 金额转分（整数，用于支付渠道） */
    public static long toFen(BigDecimal amount) {
        return scale(amount).movePointRight(2).longValueExact();
    }

    /** 分转金额 */
    public static BigDecimal fromFen(long fen) {
        return scale(BigDecimal.valueOf(fen).movePointLeft(2));
    }

    /** 向下取整为整数（积分计算用） */
    public static long floorToLong(BigDecimal v) {
        return nullToZero(v).setScale(0, RoundingMode.FLOOR).longValueExact();
    }

    /** 是否相等（按分比较，避免精度陷阱） */
    public static boolean equalsAmount(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.compareTo(b) == 0;
    }

    /** a >= b */
    public static boolean gte(BigDecimal a, BigDecimal b) {
        return nullToZero(a).compareTo(nullToZero(b)) >= 0;
    }

    /** a > b */
    public static boolean gt(BigDecimal a, BigDecimal b) {
        return nullToZero(a).compareTo(nullToZero(b)) > 0;
    }

    /** a < b */
    public static boolean lt(BigDecimal a, BigDecimal b) {
        return nullToZero(a).compareTo(nullToZero(b)) < 0;
    }

    /** 取较小值 */
    public static BigDecimal min(BigDecimal a, BigDecimal b) {
        return nullToZero(a).compareTo(nullToZero(b)) <= 0 ? scale(a) : scale(b);
    }

    /** 取较大值 */
    public static BigDecimal max(BigDecimal a, BigDecimal b) {
        return nullToZero(a).compareTo(nullToZero(b)) >= 0 ? scale(a) : scale(b);
    }

    /** 负数保护：小于 0 时返回 0 */
    public static BigDecimal atLeastZero(BigDecimal v) {
        return nullToZero(v).compareTo(BigDecimal.ZERO) < 0 ? ZERO : scale(v);
    }

    /** 展示用：百分比（0.95 → "95%"） */
    public static String toPercent(BigDecimal rate) {
        if (rate == null) {
            return "0%";
        }
        return rate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%";
    }
}
