package com.company.member.mp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.member.common.exception.BizException;
import com.company.member.common.result.ErrorCode;
import com.company.member.common.result.PageResult;
import com.company.member.common.util.MoneyUtil;
import com.company.member.modules.account.entity.MemberAccount;
import com.company.member.modules.account.entity.PointBatch;
import com.company.member.modules.account.service.AccountQueryService;
import com.company.member.modules.account.service.AccountService;
import com.company.member.modules.account.service.PointExpireService;
import com.company.member.modules.cashier.entity.Order;
import com.company.member.modules.cashier.entity.OrderItem;
import com.company.member.modules.cashier.entity.OrderPayment;
import com.company.member.modules.cashier.mapper.OrderItemMapper;
import com.company.member.modules.cashier.mapper.OrderMapper;
import com.company.member.modules.cashier.mapper.OrderPaymentMapper;
import com.company.member.modules.marketing.entity.CouponTpl;
import com.company.member.modules.marketing.entity.MemberCoupon;
import com.company.member.modules.marketing.mapper.CouponTplMapper;
import com.company.member.modules.marketing.mapper.MemberCouponMapper;
import com.company.member.modules.member.entity.LevelRule;
import com.company.member.modules.member.entity.Member;
import com.company.member.modules.member.entity.MemberLevel;
import com.company.member.modules.member.entity.MemberStat;
import com.company.member.modules.member.mapper.LevelRuleMapper;
import com.company.member.modules.member.mapper.MemberLevelMapper;
import com.company.member.modules.member.mapper.MemberMapper;
import com.company.member.modules.member.mapper.MemberStatMapper;
import com.company.member.modules.store.entity.Store;
import com.company.member.modules.store.mapper.StoreMapper;
import com.company.member.modules.system.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 小程序端 · 会员自助服务
 *
 * <p>职责：首页汇总、资产、账单、卡券、积分到期、**动态核销码**、等级与门店。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MpMemberService {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter MONTH_CN = DateTimeFormatter.ofPattern("yyyy年MM月");

    private final MemberMapper memberMapper;
    private final MemberLevelMapper levelMapper;
    private final LevelRuleMapper levelRuleMapper;
    private final MemberStatMapper statMapper;
    private final AccountService accountService;
    private final AccountQueryService accountQueryService;
    private final PointExpireService pointExpireService;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderPaymentMapper orderPaymentMapper;
    private final MemberCouponMapper couponMapper;
    private final CouponTplMapper couponTplMapper;
    private final StoreMapper storeMapper;
    private final ConfigService configService;

    /** 核销码签名密钥（生产建议独立配置） */
    @Value("${member.jwt.secret}")
    private String signSecret;

    /* ==================================================================
     *  首页汇总
     * ================================================================== */

    public Map<String, Object> homeSummary(Long memberId) {
        Member member = requireMember(memberId);
        MemberAccount account = accountService.getAccount(memberId);
        MemberStat stat = statMapper.selectOne(new LambdaQueryWrapper<MemberStat>()
                .eq(MemberStat::getMemberId, memberId));

        Map<String, Object> result = new HashMap<>();

        LevelProgress lp = calcLevelProgress(member, account);
        Map<String, Object> memberInfo = new HashMap<>();
        memberInfo.put("id", member.getId());
        memberInfo.put("memberNo", member.getMemberNo());
        memberInfo.put("nickname", member.getNickname());
        memberInfo.put("avatar", member.getAvatar());
        memberInfo.put("levelName", levelName(member.getLevelId()));
        memberInfo.put("growth", account.getGrowth());
        memberInfo.put("nextLevelName", lp.nextLevelName());
        memberInfo.put("nextLevelNeedGrowth", lp.needGrowth());
        memberInfo.put("growthProgress", lp.progress());
        result.put("member", memberInfo);

        Map<String, Object> assets = new HashMap<>();
        assets.put("balance", account.getBalance());
        assets.put("giftBalance", account.getGiftBalance());
        assets.put("points", account.getPoints());
        assets.put("growth", account.getGrowth());
        assets.put("available", account.available());
        result.put("account", assets);

        Map<String, Object> couponMap = couponMapper.summary(memberId);
        Map<String, Object> coupons = new HashMap<>();
        coupons.put("unused", num(couponMap, "unused"));
        coupons.put("used", num(couponMap, "used"));
        coupons.put("expired", num(couponMap, "expired"));
        coupons.put("expiringIn7d", calcExpiringCoupons(memberId));
        coupons.put("expiringPoints", pointExpireService.expiringSoon(memberId, 30));
        result.put("couponCount", coupons);

        Order last = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getMemberId, memberId)
                .in(Order::getStatus, Order.STATUS_PAID, Order.STATUS_PARTIAL_REFUND)
                .orderByDesc(Order::getId).last("LIMIT 1"));
        if (last != null) {
            Map<String, Object> lastOrder = new HashMap<>();
            lastOrder.put("orderNo", last.getOrderNo());
            lastOrder.put("amount", last.getPaidAmount());
            lastOrder.put("storeName", last.getStoreId() == null ? null : storeMapper.selectNameById(last.getStoreId()));
            lastOrder.put("createdAt", fmt(last.getCreatedAt()));
            result.put("lastOrder", lastOrder);
        }

        Map<String, Object> statMap = new HashMap<>();
        if (stat != null) {
            statMap.put("lastConsumeAt", fmt(stat.getLastConsumeAt()));
            statMap.put("orderCount12m", stat.getOrderCount12m() == null ? 0 : stat.getOrderCount12m());
            statMap.put("payAmount12m", stat.getPayAmount12m() == null ? BigDecimal.ZERO : stat.getPayAmount12m());
        }
        result.put("stat", statMap);

        List<Order> pending = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getMemberId, memberId)
                .in(Order::getStatus, Order.STATUS_UNPAID, Order.STATUS_PAYING)
                .orderByDesc(Order::getId).last("LIMIT 3"));
        result.put("pendingOrders", pending.stream().map(o -> {
            Map<String, Object> m = new HashMap<>();
            m.put("orderNo", o.getOrderNo());
            m.put("orderType", o.getOrderType());
            m.put("orderTypeText", Order.typeText(o.getOrderType()));
            m.put("amount", o.getPayableAmount());
            m.put("expireTime", fmt(o.getExpireTime()));
            return m;
        }).toList());

        result.put("payEnabled", configService.payEnabled() || configService.payMockEnabled());
        result.put("serverTime", LocalDateTime.now().format(DT));
        return result;
    }

    /* ==================================================================
     *  资产
     * ================================================================== */

    public Map<String, Object> account(Long memberId) {
        Member member = requireMember(memberId);
        MemberAccount account = accountService.getAccount(memberId);
        LevelProgress lp = calcLevelProgress(member, account);
        Map<String, Object> m = new HashMap<>();
        m.put("balance", account.getBalance());
        m.put("giftBalance", account.getGiftBalance());
        m.put("available", account.available());
        m.put("points", account.getPoints());
        m.put("growth", account.getGrowth());
        m.put("totalRecharge", account.getTotalRecharge());
        m.put("totalConsume", account.getTotalConsume());
        m.put("totalConsumeCount", account.getTotalConsumeCount());
        m.put("levelName", levelName(member.getLevelId()));
        m.put("nextLevelName", lp.nextLevelName());
        m.put("nextLevelNeedGrowth", lp.needGrowth());
        m.put("growthProgress", lp.progress());
        m.put("expiringPoints30d", pointExpireService.expiringSoon(memberId, 30));
        m.put("pointsExchangeRate", configService.pointsExchangeRate());
        m.put("maxDeductRatio", configService.pointsMaxDeductRatio());
        m.put("pointsPerYuan", configService.pointsPerYuan());
        return m;
    }

    public PageResult<Map<String, Object>> balanceFlows(Long memberId, String type, int pageNum, int pageSize) {
        return accountQueryService.balanceFlows(memberId, null, parseBizType(type), null, null, pageNum, pageSize);
    }

    public PageResult<Map<String, Object>> pointFlows(Long memberId, String type, int pageNum, int pageSize) {
        return accountQueryService.pointFlows(memberId, parseBizType(type), null, null, pageNum, pageSize);
    }

    /** 积分到期日历 */
    public List<Map<String, Object>> pointExpireCalendar(Long memberId) {
        List<PointBatch> batches = accountQueryService.pointBatches(memberId, true);
        Map<String, Long> byMonth = new HashMap<>();
        for (PointBatch b : batches) {
            if (b.getExpireTime() == null || b.getRemainPoints() == null || b.getRemainPoints() <= 0) {
                continue;
            }
            byMonth.merge(b.getExpireTime().format(DateTimeFormatter.ofPattern("yyyy-MM")),
                    b.getRemainPoints(), Long::sum);
        }
        return byMonth.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e -> {
            Map<String, Object> m = new HashMap<>();
            m.put("month", e.getKey());
            m.put("points", e.getValue());
            return m;
        }).toList();
    }

    /* ==================================================================
     *  账单
     * ================================================================== */

    public PageResult<Map<String, Object>> orders(Long memberId, Integer orderType, int pageNum, int pageSize) {
        var w = new LambdaQueryWrapper<Order>()
                .eq(Order::getMemberId, memberId)
                .eq(orderType != null, Order::getOrderType, orderType)
                .orderByDesc(Order::getId);
        Page<Order> page = orderMapper.selectPage(new Page<>(pageNum, Math.min(pageSize, 50)), w);

        Map<Long, String> storeNames = page.getRecords().isEmpty() ? Map.of()
                : storeMapper.selectBatchIds(page.getRecords().stream().map(Order::getStoreId)
                        .filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(Store::getId, Store::getStoreName, (a, b) -> a));

        List<Map<String, Object>> list = new ArrayList<>();
        for (Order o : page.getRecords()) {
            Map<String, Object> m = new HashMap<>();
            m.put("orderNo", o.getOrderNo());
            m.put("orderType", o.getOrderType());
            m.put("orderTypeText", Order.typeText(o.getOrderType()));
            m.put("storeName", storeNames.get(o.getStoreId()));
            m.put("totalAmount", o.getTotalAmount());
            m.put("payableAmount", o.getPayableAmount());
            m.put("paidAmount", o.getPaidAmount());
            m.put("discountAmount", MoneyUtil.add(o.getDiscountAmount(),
                    MoneyUtil.add(o.getCouponAmount(), o.getPointDeductAmount())));
            m.put("pointsGained", o.getPointsGained());
            m.put("giftAmount", o.getGiftAmount());
            m.put("status", o.getStatus());
            m.put("statusText", Order.statusText(o.getStatus()));
            m.put("refundAmount", o.getRefundAmount());
            m.put("createdAt", fmt(o.getCreatedAt()));
            m.put("month", o.getCreatedAt() == null ? null : o.getCreatedAt().format(MONTH_CN));
            List<OrderItem> items = orderItemMapper.selectByOrderId(o.getId());
            m.put("itemSummary", items.stream().map(OrderItem::getProductName).limit(3)
                    .collect(Collectors.joining("、")));
            m.put("itemCount", items.size());
            list.add(m);
        }
        return PageResult.of(list, page.getTotal(), pageNum, page.getSize());
    }

    public Map<String, Object> orderDetail(Long memberId, String orderNo) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo).last("LIMIT 1"));
        if (order == null || !memberId.equals(order.getMemberId())) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        Map<String, Object> m = new HashMap<>();
        m.put("orderNo", order.getOrderNo());
        m.put("orderType", order.getOrderType());
        m.put("orderTypeText", Order.typeText(order.getOrderType()));
        m.put("storeName", order.getStoreId() == null ? null : storeMapper.selectNameById(order.getStoreId()));
        m.put("totalAmount", order.getTotalAmount());
        m.put("discountAmount", order.getDiscountAmount());
        m.put("couponAmount", order.getCouponAmount());
        m.put("pointDeductAmount", order.getPointDeductAmount());
        m.put("pointDeductPoints", order.getPointDeductPoints());
        m.put("payableAmount", order.getPayableAmount());
        m.put("paidAmount", order.getPaidAmount());
        m.put("giftAmount", order.getGiftAmount());
        m.put("pointsGained", order.getPointsGained());
        m.put("growthGained", order.getGrowthGained());
        m.put("statusText", Order.statusText(order.getStatus()));
        m.put("refundAmount", order.getRefundAmount());
        m.put("createdAt", fmt(order.getCreatedAt()));
        m.put("paidAt", fmt(order.getPaidAt()));
        m.put("remark", order.getRemark());

        List<Map<String, Object>> items = new ArrayList<>();
        for (OrderItem it : orderItemMapper.selectByOrderId(order.getId())) {
            Map<String, Object> im = new HashMap<>();
            im.put("productName", it.getProductName());
            im.put("price", it.getPrice());
            im.put("qty", it.getQty());
            im.put("totalAmount", it.getTotalAmount());
            im.put("actualAmount", it.getActualAmount());
            items.add(im);
        }
        m.put("items", items);

        List<Map<String, Object>> payments = new ArrayList<>();
        for (OrderPayment p : orderPaymentMapper.selectByOrderId(order.getId())) {
            Map<String, Object> pm = new HashMap<>();
            pm.put("payMethod", p.getPayMethod());
            pm.put("payMethodText", OrderPayment.methodText(p.getPayMethod()));
            pm.put("accountTypeText", p.getAccountType() == null ? null
                    : (p.getAccountType() == 1 ? "本金" : "赠送"));
            pm.put("amount", p.getAmount());
            payments.add(pm);
        }
        m.put("payments", payments);
        return m;
    }

    /* ==================================================================
     *  卡券
     * ================================================================== */

    public Map<String, Object> coupons(Long memberId, Integer status, int pageNum, int pageSize) {
        var w = new LambdaQueryWrapper<MemberCoupon>()
                .eq(MemberCoupon::getMemberId, memberId)
                .eq(status != null, MemberCoupon::getStatus, status)
                .orderByAsc(MemberCoupon::getValidEnd)
                .orderByDesc(MemberCoupon::getId);
        Page<MemberCoupon> page = couponMapper.selectPage(new Page<>(pageNum, Math.min(pageSize, 50)), w);
        LocalDateTime now = LocalDateTime.now();

        List<Map<String, Object>> list = page.getRecords().stream().map(c -> {
            Map<String, Object> m = new HashMap<>();
            m.put("couponNo", c.getCouponNo());
            m.put("tplName", c.getCouponName());
            m.put("couponType", c.getCouponType());
            m.put("faceValue", c.getFaceValue());
            m.put("minAmount", c.getMinAmount());
            m.put("maxDiscount", c.getMaxDiscount());
            m.put("status", c.getStatus());
            m.put("statusText", MemberCoupon.statusText(c.getStatus()));
            m.put("source", c.getSource());
            m.put("validStart", fmt(c.getValidStart()));
            m.put("validEnd", fmt(c.getValidEnd()));
            m.put("usedAt", fmt(c.getUsedAt()));
            m.put("expiringSoon", c.getStatus() != null && c.getStatus() == MemberCoupon.STATUS_UNUSED
                    && c.getValidEnd() != null && c.getValidEnd().isBefore(now.plusDays(7)));
            m.put("daysLeft", c.getValidEnd() == null ? null
                    : Math.max(Duration.between(now, c.getValidEnd()).toDays(), 0));
            m.put("useRuleDesc", c.getMinAmount() == null || c.getMinAmount().signum() == 0
                    ? "无门槛使用" : "满 " + MoneyUtil.scale(c.getMinAmount()) + " 元可用");
            return m;
        }).toList();

        Map<String, Object> result = new HashMap<>();
        result.put("list", list);
        result.put("total", page.getTotal());
        result.put("pageNum", pageNum);
        result.put("pageSize", page.getSize());
        result.put("summary", new HashMap<>(couponMapper.summary(memberId)));
        return result;
    }

    /** 领券中心：可自助领取的券 */
    public List<Map<String, Object>> claimableCoupons(Long memberId) {
        List<CouponTpl> tpls = couponTplMapper.selectList(new LambdaQueryWrapper<CouponTpl>()
                .eq(CouponTpl::getDeleted, 0)
                .eq(CouponTpl::getStatus, 1)
                .eq(CouponTpl::getClaimable, 1)
                .orderByAsc(CouponTpl::getSortNo));
        List<Map<String, Object>> list = new ArrayList<>();
        for (CouponTpl tpl : tpls) {
            Map<String, Object> m = new HashMap<>();
            m.put("tplId", tpl.getId());
            m.put("tplName", tpl.getTplName());
            m.put("couponType", tpl.getCouponType());
            m.put("faceValue", tpl.getFaceValue());
            m.put("minAmount", tpl.getMinAmount());
            m.put("maxDiscount", tpl.getMaxDiscount());
            m.put("validType", tpl.getValidType());
            m.put("validDays", tpl.getValidDays());
            m.put("validEnd", fmt(tpl.getValidEnd()));
            m.put("useRuleDesc", tpl.getUseRuleDesc());
            m.put("limitPerMember", tpl.getLimitPerMember());
            int owned = couponMapper.countByMemberAndTpl(memberId, tpl.getId());
            m.put("owned", owned);
            m.put("canClaim", tpl.getLimitPerMember() == null || tpl.getLimitPerMember() <= 0
                    || owned < tpl.getLimitPerMember());
            m.put("remainQty", tpl.getTotalQty() == null || tpl.getTotalQty() < 0 ? -1
                    : Math.max(tpl.getTotalQty() - (tpl.getIssuedQty() == null ? 0 : tpl.getIssuedQty()), 0));
            list.add(m);
        }
        return list;
    }

    /* ==================================================================
     *  动态核销码
     * ================================================================== */

    /**
     * 生成动态核销码
     *
     * <p>内容 {@code MB:memberId:code:timestamp:sign}，签名 = HMAC-SHA256(memberId:code:timestamp) 前 16 位。
     * 60 秒有效，服务端校验时间窗 ±120 秒。
     */
    public Map<String, Object> verifyCode(Long memberId) {
        Member member = requireMember(memberId);
        long ts = System.currentTimeMillis() / 1000;
        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        String sign = hmacSha256(memberId + ":" + code + ":" + ts).substring(0, 16);
        MemberAccount account = accountService.getAccount(memberId);

        Map<String, Object> m = new HashMap<>();
        m.put("code", code);
        m.put("qrContent", "MB:" + memberId + ":" + code + ":" + ts + ":" + sign);
        m.put("expiresIn", 60);
        m.put("memberNo", member.getMemberNo());
        m.put("memberName", member.getNickname());
        m.put("levelName", levelName(member.getLevelId()));
        m.put("balance", account.available());
        m.put("points", account.getPoints());
        m.put("tip", "请向店员出示，60 秒后自动刷新");
        return m;
    }

    public Map<String, Object> staticCode(Long memberId) {
        Member member = requireMember(memberId);
        Map<String, Object> m = new HashMap<>();
        m.put("qrContent", "MBSTATIC:" + member.getMemberNo());
        m.put("memberNo", member.getMemberNo());
        m.put("tip", "静态码仅用于识别身份，扣款请使用动态核销码");
        return m;
    }

    /** 校验动态核销码（收银端扫码后调用） */
    public Map<String, Object> verifyDynamicCode(String qrContent, int toleranceSeconds) {
        if (qrContent == null || !qrContent.startsWith("MB:")) {
            throw new BizException(ErrorCode.PARAM_INVALID, "核销码格式不正确");
        }
        String[] parts = qrContent.split(":");
        if (parts.length != 5) {
            throw new BizException(ErrorCode.PARAM_INVALID, "核销码格式不正确");
        }
        Long memberId;
        long ts;
        try {
            memberId = Long.valueOf(parts[1]);
            ts = Long.parseLong(parts[3]);
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "核销码格式不正确");
        }
        String code = parts[2];
        String sign = parts[4];
        long now = System.currentTimeMillis() / 1000;
        if (Math.abs(now - ts) > toleranceSeconds) {
            throw new BizException(ErrorCode.PARAM_INVALID, "核销码已过期，请让会员刷新后重试");
        }
        String expect = hmacSha256(memberId + ":" + code + ":" + ts).substring(0, 16);
        if (!expect.equalsIgnoreCase(sign)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "核销码校验失败，可能被篡改");
        }
        Member member = requireMember(memberId);
        MemberAccount account = accountService.getAccount(memberId);
        Map<String, Object> m = new HashMap<>();
        m.put("memberId", memberId);
        m.put("memberNo", member.getMemberNo());
        m.put("nickname", member.getNickname());
        m.put("phoneMasked", MpAuthService.maskPhone(member.getPhone()));
        m.put("levelName", levelName(member.getLevelId()));
        m.put("balance", account.getBalance());
        m.put("giftBalance", account.getGiftBalance());
        m.put("points", account.getPoints());
        m.put("status", member.getStatus());
        return m;
    }

    private String hmacSha256(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.error("[小程序] 核销码签名失败", e);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "核销码生成失败");
        }
    }

    /* ==================================================================
     *  等级 / 门店
     * ================================================================== */

    public List<Map<String, Object>> levels(Long memberId) {
        Member member = memberId == null ? null : memberMapper.selectById(memberId);
        MemberAccount account = memberId == null ? null : accountService.getAccount(memberId);
        return levelMapper.selectList(new LambdaQueryWrapper<MemberLevel>()
                        .eq(MemberLevel::getStatus, 1).eq(MemberLevel::getDeleted, 0)
                        .orderByAsc(MemberLevel::getLevelValue)).stream()
                .map(l -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", l.getId());
                    m.put("levelName", l.getLevelName());
                    m.put("levelValue", l.getLevelValue());
                    m.put("discountRate", l.getDiscountRate());
                    m.put("discountText", l.getDiscountRate() == null ? "无折扣"
                            : (l.getDiscountRate().compareTo(BigDecimal.ONE) >= 0 ? "无折扣"
                            : MoneyUtil.toPercent(l.getDiscountRate())));
                    m.put("pointsRate", l.getPointsRate());
                    m.put("remark", l.getRemark());
                    m.put("growthThreshold", levelThreshold(l.getId()));
                    m.put("current", member != null && l.getId().equals(member.getLevelId()));
                    m.put("achieved", member != null && account != null
                            && account.getGrowth() != null
                            && account.getGrowth() >= levelThreshold(l.getId()));
                    return m;
                }).toList();
    }

    public List<Map<String, Object>> stores() {
        return storeMapper.selectList(new LambdaQueryWrapper<Store>()
                        .eq(Store::getDeleted, 0).eq(Store::getStatus, 1)
                        .orderByAsc(Store::getSortNo)).stream()
                .map(s -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", s.getId());
                    m.put("storeName", s.getStoreName());
                    m.put("address", s.getAddress());
                    m.put("contactPhone", s.getContactPhone());
                    m.put("businessHours", s.getBusinessHours());
                    m.put("longitude", s.getLongitude());
                    m.put("latitude", s.getLatitude());
                    return m;
                }).toList();
    }

    /* ==================================================================
     *  内部
     * ================================================================== */

    private Member requireMember(Long memberId) {
        Member member = memberMapper.selectById(memberId);
        if (member == null) {
            throw new BizException(ErrorCode.MEMBER_NOT_FOUND);
        }
        if (member.isCancelled()) {
            throw new BizException(ErrorCode.MEMBER_CANCELLED);
        }
        if (member.isFrozen()) {
            throw new BizException(ErrorCode.MEMBER_FROZEN, "会员卡已被冻结，请联系门店");
        }
        return member;
    }

    private String levelName(Long levelId) {
        if (levelId == null) {
            return null;
        }
        MemberLevel l = levelMapper.selectById(levelId);
        return l == null ? null : l.getLevelName();
    }

    private record LevelProgress(String nextLevelName, Long needGrowth, BigDecimal progress) {
    }

    private LevelProgress calcLevelProgress(Member member, MemberAccount account) {
        List<MemberLevel> levels = levelMapper.selectList(new LambdaQueryWrapper<MemberLevel>()
                .eq(MemberLevel::getStatus, 1).eq(MemberLevel::getDeleted, 0)
                .orderByAsc(MemberLevel::getLevelValue));
        if (levels.isEmpty()) {
            return new LevelProgress(null, null, BigDecimal.ZERO);
        }
        MemberLevel current = levels.stream().filter(l -> l.getId().equals(member.getLevelId()))
                .findFirst().orElse(levels.get(0));
        MemberLevel next = levels.stream()
                .filter(l -> l.getLevelValue() > current.getLevelValue())
                .min(Comparator.comparingInt(MemberLevel::getLevelValue)).orElse(null);
        if (next == null) {
            return new LevelProgress(null, 0L, BigDecimal.ONE);
        }
        Long threshold = levelThreshold(next.getId());
        long growth = account.getGrowth() == null ? 0L : account.getGrowth();
        long need = threshold == Long.MAX_VALUE ? 0L : Math.max(threshold - growth, 0);
        BigDecimal progress = (threshold == null || threshold <= 0 || threshold == Long.MAX_VALUE)
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(Math.min((double) growth / threshold, 1.0)).setScale(4, RoundingMode.HALF_UP);
        return new LevelProgress(next.getLevelName(), need, progress);
    }

    /** 从等级规则读取成长值门槛（rule_json 中首个 value 数字） */
    private Long levelThreshold(Long levelId) {
        try {
            LevelRule rule = levelRuleMapper.selectOne(new LambdaQueryWrapper<LevelRule>()
                    .eq(LevelRule::getLevelId, levelId)
                    .eq(LevelRule::getRuleType, 1)
                    .eq(LevelRule::getStatus, 1)
                    .orderByDesc(LevelRule::getPriority)
                    .last("LIMIT 1"));
            if (rule == null || rule.getRuleJson() == null) {
                return Long.MAX_VALUE;
            }
            String json = rule.getRuleJson();
            int idx = json.indexOf("\"value\"");
            if (idx < 0) {
                return Long.MAX_VALUE;
            }
            String sub = json.substring(idx);
            int colon = sub.indexOf(':');
            StringBuilder sb = new StringBuilder();
            for (char c : sub.substring(colon + 1).toCharArray()) {
                if (Character.isDigit(c)) {
                    sb.append(c);
                } else if (sb.length() > 0) {
                    break;
                }
            }
            return sb.length() == 0 ? Long.MAX_VALUE : Long.parseLong(sb.toString());
        } catch (Exception e) {
            log.debug("[小程序] 读取等级门槛失败 levelId={}: {}", levelId, e.getMessage());
            return Long.MAX_VALUE;
        }
    }

    private int calcExpiringCoupons(Long memberId) {
        LocalDateTime now = LocalDateTime.now();
        Long count = couponMapper.selectCount(new LambdaQueryWrapper<MemberCoupon>()
                .eq(MemberCoupon::getMemberId, memberId)
                .eq(MemberCoupon::getStatus, MemberCoupon.STATUS_UNUSED)
                .le(MemberCoupon::getValidEnd, now.plusDays(7))
                .ge(MemberCoupon::getValidEnd, now));
        return count == null ? 0 : count.intValue();
    }

    private static Integer parseBizType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        return switch (type.toLowerCase()) {
            case "recharge" -> AccountService.BIZ_RECHARGE;
            case "consume" -> AccountService.BIZ_CONSUME;
            case "refund" -> AccountService.BIZ_REFUND;
            case "adjust" -> AccountService.BIZ_ADJUST;
            case "gain" -> AccountService.PBIZ_CONSUME_GAIN;
            case "exchange" -> AccountService.PBIZ_EXCHANGE;
            case "expire" -> AccountService.PBIZ_EXPIRE;
            default -> null;
        };
    }

    private static int num(Map<String, Object> map, String key) {
        if (map == null) {
            return 0;
        }
        Object v = map.get(key);
        return v == null ? 0 : ((Number) v).intValue();
    }

    private static String fmt(LocalDateTime dt) {
        return dt == null ? null : dt.format(DT);
    }
}
