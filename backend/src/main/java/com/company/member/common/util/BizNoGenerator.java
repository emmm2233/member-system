package com.company.member.common.util;

import com.company.member.common.cache.CacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * 业务单号生成器
 *
 * <p>编号规则（便于人工识别与日志检索）：
 * <pre>
 *   会员   M  + yyyyMMdd + 6位日序列      M20260914000123
 *   消费单 O  + yyyyMMddHHmmss + 3位序列  O20260914104233001
 *   充值单 R  + ...                       R20260914103025001
 *   售卡单 C  + ...                       C20260914110000001
 *   兑换单 X  + ...                       X20260914113000001
 *   支付单 PAY+ yyyyMMddHHmmss + 3位       PAY20260914103025001
 *   退款单 RF + ...                       RF20260914120000001
 *   核销单 V  + ...                       V20260914110500123
 *   余额流水 BF+ ...                      BF20260914104233001
 *   积分流水 PF+ ... / 成长值 GF+ ...
 *   券码   CP + 16位随机（无易混字符）
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BizNoGenerator {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter SECOND = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    /** 券码字符集：去掉 0 O 1 I 等易混字符 */
    private static final char[] COUPON_CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CacheService cacheService;

    /* ---------- 静态前缀 ---------- */
    public static final String P_MEMBER = "M";
    public static final String P_ORDER = "O";
    public static final String P_RECHARGE = "R";
    public static final String P_CARD_SALE = "C";
    public static final String P_EXCHANGE = "X";
    public static final String P_PAYMENT = "PAY";
    public static final String P_REFUND = "RF";
    public static final String P_VERIFY = "V";
    public static final String P_BALANCE_FLOW = "BF";
    public static final String P_POINT_FLOW = "PF";
    public static final String P_GROWTH_FLOW = "GF";
    public static final String P_COUPON = "CP";
    public static final String P_GRANT_TASK = "GT";
    public static final String P_IMPORT_TASK = "IM";
    public static final String P_SHIFT = "SH";

    /** 会员号：M + yyyyMMdd + 6 位日序列 */
    public String memberNo() {
        return P_MEMBER + LocalDate.now().format(DAY) + dailySeq("member", 6);
    }

    /** 订单类单号：前缀 + 秒级时间 + 3 位序列 */
    public String orderNo(String prefix) {
        return prefix + LocalDateTime.now().format(SECOND) + secondSeq(prefix, 3);
    }

    public String consumeOrderNo() {
        return orderNo(P_ORDER);
    }

    public String rechargeOrderNo() {
        return orderNo(P_RECHARGE);
    }

    public String cardSaleOrderNo() {
        return orderNo(P_CARD_SALE);
    }

    public String exchangeOrderNo() {
        return orderNo(P_EXCHANGE);
    }

    public String paymentNo() {
        return orderNo(P_PAYMENT);
    }

    public String refundNo() {
        return orderNo(P_REFUND);
    }

    public String verifyNo() {
        return orderNo(P_VERIFY);
    }

    public String balanceFlowNo() {
        return orderNo(P_BALANCE_FLOW);
    }

    public String pointFlowNo() {
        return orderNo(P_POINT_FLOW);
    }

    public String growthFlowNo() {
        return orderNo(P_GROWTH_FLOW);
    }

    public String grantTaskNo() {
        return orderNo(P_GRANT_TASK);
    }

    public String importTaskNo() {
        return orderNo(P_IMPORT_TASK);
    }

    public String shiftNo(long storeId) {
        return P_SHIFT + LocalDate.now().format(DAY) + "S" + storeId + "-" + dailySeq("shift:" + storeId, 2);
    }

    /** 券码：CP + 16 位随机（无易混字符） */
    public String couponNo() {
        StringBuilder sb = new StringBuilder(P_COUPON);
        for (int i = 0; i < 16; i++) {
            sb.append(COUPON_CHARS[RANDOM.nextInt(COUPON_CHARS.length)]);
        }
        return sb.toString();
    }

    /** 通用单号（自定义前缀 + 秒级时间 + 3 位序列） */
    public String custom(String prefix) {
        return orderNo(prefix);
    }

    private String dailySeq(String biz, int width) {
        String key = "seq:" + biz + ":" + LocalDate.now().format(DAY);
        long v = cacheService.increment(key, 1, 48);
        return pad(v, width);
    }

    private String secondSeq(String biz, int width) {
        String key = "seq:" + biz + ":" + LocalDateTime.now().format(SECOND);
        long v = cacheService.increment(key, 1, 1);
        return pad(v, width);
    }

    private static String pad(long v, int width) {
        String s = String.valueOf(v);
        if (s.length() >= width) {
            return s.substring(s.length() - width);
        }
        return "0".repeat(width - s.length()) + s;
    }

    /** 生成 6 位数字动态码（会员核销码用） */
    public String dynamicCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    /**
     * 生成单号并保证在「唯一键冲突」时自动重试
     *
     * <p>背景：本地缓存实现（Caffeine）的序列计数器不跨实例、也不跨重启持久化，
     * 手工清库或多次重启后计数器可能与数据库既有数据重叠。该方法在插入前先做存在性校验，
     * 冲突则重新生成，让上层业务无需关心序列一致性问题。
     *
     * @param generator 单号生成函数
     * @param exists    存在性校验函数（返回 true 表示该单号已存在）
     */
    public String generateWithRetry(java.util.function.Supplier<String> generator,
                                    java.util.function.Predicate<String> exists) {
        for (int i = 0; i < 8; i++) {
            String no = generator.get();
            if (!exists.test(no)) {
                return no;
            }
            log.warn("[单号] {} 已存在，自动重新生成（第 {} 次）", no, i + 1);
        }
        throw new IllegalStateException("单号生成连续冲突 8 次，请检查序列计数器与数据库是否严重不一致");
    }

    /** 生成幂等键（服务端兜底用） */
    public String idempotencyKey(String biz, String bizNo) {
        return biz + ":" + bizNo;
    }

    /** 简单等待（供重试场景使用） */
    public void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
