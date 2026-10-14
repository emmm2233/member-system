package com.company.member.modules.marketing.service;

import com.company.member.common.exception.BizException;
import com.company.member.common.result.ErrorCode;
import com.company.member.common.util.BizNoGenerator;
import com.company.member.common.util.MoneyUtil;
import com.company.member.modules.marketing.entity.CouponTpl;
import com.company.member.modules.marketing.entity.MemberCoupon;
import com.company.member.modules.marketing.mapper.CouponTplMapper;
import com.company.member.modules.marketing.mapper.MemberCouponMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 券服务：发券 / 锁定 / 核销 / 撤销 / 过期
 *
 * <p>关键设计：
 * <ul>
 *   <li>发券时把模板关键字段**固化为快照**，模板后续修改不影响已发券</li>
 *   <li>核销采用「先锁定后使用」两段式，解决多收银台并发用同一张券</li>
 *   <li>发行量用 SQL 原子递增 + 条件判断，防并发超发</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponTplMapper tplMapper;
    private final MemberCouponMapper couponMapper;
    private final BizNoGenerator noGenerator;

    /* ==================== 发券 ==================== */

    /**
     * 发放券
     *
     * @param source      来源：GRANT/CLAIM/REFERRAL/BIRTHDAY/RECHARGE/ACTIVITY/EXCHANGE
     * @param sourceBizNo 来源业务单号
     * @return 已发放的券列表
     */
    @Transactional(rollbackFor = Exception.class)
    public List<MemberCoupon> grant(Long memberId, Long tplId, int qty, String source, String sourceBizNo) {
        CouponTpl tpl = tplMapper.selectById(tplId);
        if (tpl == null || (tpl.getStatus() != null && tpl.getStatus() == 0)) {
            throw new BizException(ErrorCode.COUPON_UNAVAILABLE, "券模板不存在或已停用");
        }
        if (qty < 1) {
            throw new BizException(ErrorCode.PARAM_INVALID, "发放数量必须大于 0");
        }
        // 每人限领校验
        if (tpl.getLimitPerMember() != null && tpl.getLimitPerMember() > 0) {
            int owned = couponMapper.countByMemberAndTpl(memberId, tplId);
            if (owned + qty > tpl.getLimitPerMember()) {
                throw new BizException(ErrorCode.COUPON_LIMIT_EXCEEDED,
                        "每人最多可领 " + tpl.getLimitPerMember() + " 张，当前已持有 " + owned + " 张");
            }
        }
        // 发行量原子递增（防超发）
        int issued = tplMapper.increaseIssued(tplId, qty);
        if (issued == 0) {
            throw new BizException(ErrorCode.COUPON_SOLD_OUT);
        }

        List<MemberCoupon> result = new ArrayList<>(qty);
        for (int i = 0; i < qty; i++) {
            MemberCoupon coupon = buildCoupon(memberId, tpl, source, sourceBizNo);
            couponMapper.insert(coupon);
            result.add(coupon);
        }
        log.info("[券] 发放成功 memberId={}, tplId={}, qty={}, source={}", memberId, tplId, qty, source);
        return result;
    }

    /** 发券（不校验限领，用于活动/推荐奖励等系统发放） */
    @Transactional(rollbackFor = Exception.class)
    public List<MemberCoupon> grantForce(Long memberId, Long tplId, int qty, String source, String sourceBizNo) {
        CouponTpl tpl = tplMapper.selectById(tplId);
        if (tpl == null) {
            throw new BizException(ErrorCode.COUPON_UNAVAILABLE, "券模板不存在");
        }
        int issued = tplMapper.increaseIssued(tplId, qty);
        if (issued == 0) {
            log.warn("[券] 发行量已满，跳过发放 tplId={}", tplId);
            return List.of();
        }
        List<MemberCoupon> result = new ArrayList<>(qty);
        for (int i = 0; i < qty; i++) {
            MemberCoupon coupon = buildCoupon(memberId, tpl, source, sourceBizNo);
            couponMapper.insert(coupon);
            result.add(coupon);
        }
        return result;
    }

    private MemberCoupon buildCoupon(Long memberId, CouponTpl tpl, String source, String sourceBizNo) {
        MemberCoupon c = new MemberCoupon();
        c.setCouponNo(noGenerator.couponNo());
        c.setTplId(tpl.getId());
        c.setMemberId(memberId);
        c.setCouponType(tpl.getCouponType());
        c.setCouponName(tpl.getTplName());
        c.setFaceValue(tpl.getFaceValue());
        c.setMinAmount(tpl.getMinAmount() == null ? BigDecimal.ZERO : tpl.getMinAmount());
        c.setMaxDiscount(tpl.getMaxDiscount());
        c.setScopeStore(tpl.getScopeStore() == null ? "-1" : tpl.getScopeStore());
        c.setScopeProduct(tpl.getScopeProduct() == null ? "-1" : tpl.getScopeProduct());
        c.setAllowStack(tpl.getAllowStack() == null ? 0 : tpl.getAllowStack());
        c.setStackGroup(tpl.getStackGroup());
        c.setTplSnapshot(buildSnapshot(tpl));
        c.setStatus(MemberCoupon.STATUS_UNUSED);
        c.setSource(source == null ? "GRANT" : source);
        c.setSourceBizNo(sourceBizNo);
        // 有效期在发券时固化
        LocalDateTime now = LocalDateTime.now();
        if (tpl.getValidType() != null && tpl.getValidType() == 2 && tpl.getValidDays() != null) {
            c.setValidStart(now);
            c.setValidEnd(now.plusDays(tpl.getValidDays()));
        } else {
            c.setValidStart(tpl.getValidStart() == null ? now : tpl.getValidStart());
            c.setValidEnd(tpl.getValidEnd() == null ? now.plusYears(1) : tpl.getValidEnd());
        }
        c.setTenantId(0L);
        return c;
    }

    private String buildSnapshot(CouponTpl tpl) {
        return String.format(
                "{\"tplId\":%d,\"name\":\"%s\",\"type\":%d,\"faceValue\":%s,\"minAmount\":%s,\"maxDiscount\":%s}",
                tpl.getId(), tpl.getTplName() == null ? "" : tpl.getTplName().replace("\"", ""),
                tpl.getCouponType(), tpl.getFaceValue(), tpl.getMinAmount(), tpl.getMaxDiscount());
    }

    /* ==================== 查询 ==================== */

    public List<MemberCoupon> usableCoupons(Long memberId) {
        return couponMapper.selectUsable(memberId, LocalDateTime.now());
    }

    public MemberCoupon getByCouponNo(String couponNo) {
        MemberCoupon c = couponMapper.selectByCouponNo(couponNo);
        if (c == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "优惠券不存在");
        }
        return c;
    }

    public java.util.Map<String, Object> summary(Long memberId) {
        java.util.Map<String, Object> m = couponMapper.summary(memberId);
        return m == null ? new java.util.HashMap<>() : m;
    }

    /** 按来源业务单号查券（充值撤销时作废赠券用） */
    public List<MemberCoupon> listBySourceBizNo(String sourceBizNo) {
        if (sourceBizNo == null || sourceBizNo.isBlank()) {
            return List.of();
        }
        return couponMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MemberCoupon>()
                .eq(MemberCoupon::getSourceBizNo, sourceBizNo));
    }

    /* ==================== 锁定 / 释放 ==================== */

    /** 锁定券（进入结算时调用，TTL 由定时任务释放） */
    @Transactional(rollbackFor = Exception.class)
    public void lock(Long couponId, String orderNo) {
        int rows = couponMapper.lock(couponId, orderNo);
        if (rows == 0) {
            throw new BizException(ErrorCode.COUPON_LOCKED);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void unlock(Long couponId) {
        couponMapper.unlock(couponId);
    }

    /* ==================== 校验与抵扣计算 ==================== */

    /**
     * 计算券在当前订单下的抵扣金额（并校验可用性）
     *
     * @param orderAmount 券基准金额（折后价，不含券）
     * @return 抵扣金额
     */
    public BigDecimal calcDiscount(MemberCoupon coupon, BigDecimal orderAmount, Long storeId, List<Long> productIds) {
        validateUsable(coupon, orderAmount, storeId, productIds);
        return doCalcDiscount(coupon, orderAmount);
    }

    /** 只计算不校验（用于试算展示） */
    public BigDecimal doCalcDiscount(MemberCoupon coupon, BigDecimal orderAmount) {
        Integer type = coupon.getCouponType();
        BigDecimal face = coupon.getFaceValue() == null ? BigDecimal.ZERO : coupon.getFaceValue();
        BigDecimal discount;
        if (type != null && type == CouponTpl.TYPE_DISCOUNT) {
            // 折扣券：faceValue 为折扣率，如 0.88
            BigDecimal rate = face.compareTo(BigDecimal.ONE) > 0
                    ? face.divide(BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP) : face;
            discount = MoneyUtil.mul(orderAmount, BigDecimal.ONE.subtract(rate));
            if (coupon.getMaxDiscount() != null && MoneyUtil.gt(discount, coupon.getMaxDiscount())) {
                discount = coupon.getMaxDiscount();
            }
        } else {
            // 满减 / 代金 / 兑换：直接扣面额
            discount = face;
        }
        // 抵扣不得超过订单金额
        if (MoneyUtil.gt(discount, orderAmount)) {
            discount = orderAmount;
        }
        return MoneyUtil.atLeastZero(discount);
    }

    /** 可用性校验链（严格按序，任一失败即拒绝） */
    public void validateUsable(MemberCoupon coupon, BigDecimal orderAmount, Long storeId, List<Long> productIds) {
        validateUnlockedUsable(coupon);
        if (MoneyUtil.lt(orderAmount, coupon.getMinAmount())) {
            throw new BizException(ErrorCode.COUPON_THRESHOLD_NOT_MET,
                    "订单金额未满 " + coupon.getMinAmount() + " 元，无法使用该券");
        }
        if (storeId != null && !scopeContains(coupon.getScopeStore(), storeId)) {
            throw new BizException(ErrorCode.COUPON_SCOPE_MISMATCH, "该券不适用于当前门店");
        }
        if (productIds != null && !productIds.isEmpty() && !"-1".equals(coupon.getScopeProduct())) {
            boolean matched = productIds.stream().anyMatch(pid -> scopeContains(coupon.getScopeProduct(), pid));
            if (!matched) {
                throw new BizException(ErrorCode.COUPON_SCOPE_MISMATCH, "该券不适用于所选商品");
            }
        }
    }

    /** 仅校验状态与有效期（不含订单相关条件） */
    public void validateUnlockedUsable(MemberCoupon coupon) {
        if (coupon.getStatus() == null || coupon.getStatus() != MemberCoupon.STATUS_UNUSED) {
            if (coupon.getStatus() != null && coupon.getStatus() == MemberCoupon.STATUS_USED) {
                throw new BizException(ErrorCode.COUPON_USED);
            }
            throw new BizException(ErrorCode.COUPON_UNAVAILABLE);
        }
        LocalDateTime now = LocalDateTime.now();
        if (coupon.getValidEnd() != null && now.isAfter(coupon.getValidEnd())) {
            throw new BizException(ErrorCode.COUPON_EXPIRED);
        }
        if (coupon.getValidStart() != null && now.isBefore(coupon.getValidStart())) {
            throw new BizException(ErrorCode.COUPON_UNAVAILABLE, "优惠券尚未生效");
        }
    }

    /* ==================== 核销 / 撤销 ==================== */

    /** 核销（订单支付成功后调用，或扫码直接核销） */
    @Transactional(rollbackFor = Exception.class)
    public void use(Long couponId, Long orderId, Long storeId) {
        MemberCoupon coupon = couponMapper.selectById(couponId);
        if (coupon == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "优惠券不存在");
        }
        int rows = couponMapper.markUsed(couponId, orderId, storeId);
        if (rows == 0) {
            throw new BizException(ErrorCode.COUPON_USED);
        }
        tplMapper.increaseUsed(coupon.getTplId(), 1);
        log.info("[券] 核销 couponNo={}, orderId={}, storeId={}", coupon.getCouponNo(), orderId, storeId);
    }

    /** 退单恢复券：未过期恢复可用，已过期置过期 */
    @Transactional(rollbackFor = Exception.class)
    public void restoreOnRefund(Long couponId) {
        MemberCoupon coupon = couponMapper.selectById(couponId);
        if (coupon == null || coupon.getStatus() == null || coupon.getStatus() != MemberCoupon.STATUS_USED) {
            return;
        }
        boolean expired = coupon.getValidEnd() != null && LocalDateTime.now().isAfter(coupon.getValidEnd());
        couponMapper.restore(couponId, expired ? MemberCoupon.STATUS_EXPIRED : MemberCoupon.STATUS_UNUSED);
        log.info("[券] 退单恢复 couponNo={}, 恢复状态={}", coupon.getCouponNo(),
                expired ? "已过期" : "未使用");
    }

    /** 作废券 */
    @Transactional(rollbackFor = Exception.class)
    public void voidCoupon(Long couponId, String reason) {
        MemberCoupon coupon = couponMapper.selectById(couponId);
        if (coupon == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "优惠券不存在");
        }
        if (coupon.getStatus() != null && coupon.getStatus() == MemberCoupon.STATUS_USED) {
            throw new BizException(ErrorCode.COUPON_USED, "已使用的券不可作废");
        }
        coupon.setStatus(MemberCoupon.STATUS_VOID);
        coupon.setRemark(reason);
        couponMapper.updateById(coupon);
        log.warn("[券] 作废 couponNo={}, 原因={}", coupon.getCouponNo(), reason);
    }

    /* ==================== 过期任务 ==================== */

    /** 过期扫描（分批游标） */
    public int expireBatch(int limit) {
        int total = 0;
        long lastId = 0L;
        while (true) {
            List<MemberCoupon> list = couponMapper.selectExpired(LocalDateTime.now(), lastId, limit);
            if (list.isEmpty()) {
                break;
            }
            for (MemberCoupon c : list) {
                total += couponMapper.markExpired(c.getId());
                lastId = c.getId();
            }
            if (list.size() < limit) {
                break;
            }
        }
        return total;
    }

    /** 释放超时锁（默认 5 分钟） */
    @Transactional(rollbackFor = Exception.class)
    public int unlockTimeout(int minutes) {
        return couponMapper.unlockTimeout(LocalDateTime.now().minusMinutes(minutes));
    }

    private static boolean scopeContains(String scope, Long id) {
        if (scope == null || scope.isBlank() || "-1".equals(scope.trim())) {
            return true;
        }
        for (String s : scope.split(",")) {
            if (s.trim().equals(String.valueOf(id))) {
                return true;
            }
        }
        return false;
    }
}
