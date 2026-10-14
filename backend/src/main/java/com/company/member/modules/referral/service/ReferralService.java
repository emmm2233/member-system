package com.company.member.modules.referral.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.member.common.exception.BizException;
import com.company.member.common.result.ErrorCode;
import com.company.member.common.result.PageResult;
import com.company.member.common.util.MaskUtil;
import com.company.member.modules.account.dto.AccountChangeCmd;
import com.company.member.modules.account.service.AccountService;
import com.company.member.modules.cashier.entity.Order;
import com.company.member.modules.marketing.entity.MemberCoupon;
import com.company.member.modules.marketing.service.CouponService;
import com.company.member.modules.member.entity.Member;
import com.company.member.modules.member.mapper.MemberMapper;
import com.company.member.modules.message.service.MessageService;
import com.company.member.modules.referral.entity.Referral;
import com.company.member.modules.referral.entity.ReferralReward;
import com.company.member.modules.referral.mapper.ReferralMapper;
import com.company.member.modules.referral.mapper.ReferralRewardMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 推荐有礼
 *
 * <p><b>三个动作，三段职责</b>：
 * <ol>
 *   <li><b>绑定</b> {@link #bind}：被推荐人填写邀请码 → 校验防刷 → 落 m_referral（invitee_id 唯一索引兜底）</li>
 *   <li><b>结算</b> {@link #settleIfEligible}：达成触发条件（注册/首单/首次充值）→ 用条件 UPDATE
 *       抢占结算权，抢到的线程才发奖</li>
 *   <li><b>发奖</b> {@link #issueRewards}：先落 m_referral_reward（待发放），再调资产服务，
 *       成功置已发放、失败留原因。资产幂等键 = {@code REF:{referralId}:{target}}，
 *       与奖励记录一一对应，即便被重复调用也不会重复加钱</li>
 * </ol>
 *
 * <p><b>为什么结算和发奖要分开事务</b>：结算标记一旦落库就不能回退（否则可能重复发奖），
 * 而发奖可能因券模板下架、账户冻结等临时原因失败。分开之后，失败的奖励记录留在库里，
 * 由 {@link #retryPendingRewards} 与 {@link #reconcileSettled} 两个补偿入口自愈。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReferralService {

    /** 邀请码校验位字符集：去掉 0O1I 等易混字符 */
    private static final char[] CODE_CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final int SIG_LEN = 4;

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final ReferralMapper referralMapper;
    private final ReferralRewardMapper rewardMapper;
    private final ReferralConfigService configService;
    private final MemberMapper memberMapper;
    private final AccountService accountService;
    private final CouponService couponService;
    private final MessageService messageService;

    /* ==================== 1. 邀请码 ==================== */

    /**
     * 生成会员的邀请码：{@code base36(memberId) + 4位HMAC校验位}
     *
     * <p>不落库、可逆、不可伪造：改了任何一个字符都会导致校验位不匹配。
     * 这样「邀请码 → 会员」的解析是纯计算，不需要额外的码表，
     * 也避免了码表被遍历枚举。
     */
    public String inviteCode(Long memberId) {
        if (memberId == null) {
            return null;
        }
        String payload = Long.toString(memberId, 36).toUpperCase();
        return payload + signature(payload);
    }

    /** 解析邀请码；非法或校验失败返回 null（不抛异常，便于接口统一提示） */
    public Long resolveInviteCode(String code) {
        if (code == null) {
            return null;
        }
        // 兼容小程序码 scene 参数与用户手输的大写/小写、含空格情形
        String c = code.trim().toUpperCase().replace(" ", "");
        if (c.length() <= SIG_LEN) {
            return null;
        }
        String payload = c.substring(0, c.length() - SIG_LEN);
        String sig = c.substring(c.length() - SIG_LEN);
        if (!signature(payload).equals(sig)) {
            return null;
        }
        try {
            long id = Long.parseLong(payload, 36);
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String signature(String payload) {
        String secret = configService.secret();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(SIG_LEN);
            for (int i = 0; i < SIG_LEN; i++) {
                sb.append(CODE_CHARS[(digest[i] & 0xFF) % CODE_CHARS.length]);
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("邀请码签名失败", e);
        }
    }

    private String secretOf() {
        // 单独取一次，避免 load() 之后配置被清缓存导致签名不一致
        return configService.secret();
    }

    /* ==================== 2. 绑定 ==================== */

    /**
     * 绑定推荐关系
     *
     * @param inviteeId  被推荐人（当前登录会员）
     * @param inviteCode 邀请码
     * @param channel    渠道：POSTER/LINK/QRCODE
     * @return 绑定后的推荐关系
     */
    @Transactional(rollbackFor = Exception.class)
    public Referral bind(Long inviteeId, String inviteCode, String channel) {
        ReferralConfigService.Cfg cfg = configService.load();
        if (!cfg.enabled()) {
            throw new BizException(ErrorCode.OPERATION_FAILED, "推荐有礼活动未开启");
        }
        Long referrerId = resolveInviteCode(inviteCode);
        if (referrerId == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "邀请码无效，请确认后重试");
        }

        Member invitee = memberMapper.selectById(inviteeId);
        Member referrer = memberMapper.selectById(referrerId);
        validateBind(invitee, referrer, cfg);

        String ch = channel == null || channel.isBlank() ? Referral.CH_LINK : channel.trim().toUpperCase();

        Referral referral = new Referral();
        referral.setReferrerId(referrer.getId());
        referral.setInviteeId(invitee.getId());
        referral.setChannel(ch);
        referral.setStatus(Referral.ST_BOUND);
        referral.setBindAt(LocalDateTime.now());
        try {
            referralMapper.insert(referral);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // uk_invitee：并发下两个推荐人同时抢同一个新人
            throw new BizException(ErrorCode.OPERATION_FAILED, "该会员已绑定过邀请人");
        }

        // 回写 m_member.referrer_id，来源标记为「推荐注册」
        Member patch = new Member();
        patch.setId(invitee.getId());
        patch.setReferrerId(referrer.getId());
        if (invitee.getSource() != null && invitee.getSource() == 2) {
            patch.setSource(4);
        }
        memberMapper.updateById(patch);

        log.info("[推荐] 绑定成功 referrerId={}, inviteeId={}, channel={}", referrer.getId(), invitee.getId(), ch);

        // 注册即奖模式：绑定时立刻结算，不等消费
        if ("REGISTER".equals(cfg.settleMode())) {
            settle(referral, "REGISTER:" + referral.getId());
        }
        return referral;
    }

    /** 防刷校验链：任一不满足即拒绝，并给出可操作的中文原因 */
    private void validateBind(Member invitee, Member referrer, ReferralConfigService.Cfg cfg) {
        if (invitee == null) {
            throw new BizException(ErrorCode.MEMBER_NOT_FOUND);
        }
        if (referrer == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "邀请人不存在");
        }
        if (invitee.getId().equals(referrer.getId())) {
            throw new BizException(ErrorCode.OPERATION_FAILED, "不能填写自己的邀请码");
        }
        if (invitee.getStatus() != null && invitee.getStatus() != 1) {
            throw new BizException(ErrorCode.OPERATION_FAILED, "当前会员状态无法参与推荐活动");
        }
        if (referrer.getStatus() != null && referrer.getStatus() != 1) {
            throw new BizException(ErrorCode.OPERATION_FAILED, "邀请人账号状态异常，无法绑定");
        }
        if (invitee.getReferrerId() != null || referralMapper.selectByInvitee(invitee.getId()) != null) {
            throw new BizException(ErrorCode.OPERATION_FAILED, "您已绑定过邀请人，无法修改");
        }
        if (cfg.requirePhone() && isBlank(invitee.getPhone())) {
            throw new BizException(ErrorCode.OPERATION_FAILED, "请先绑定手机号后再填写邀请码");
        }
        // 同一手机号自荐（用小号刷奖励）
        if (!isBlank(invitee.getPhone()) && invitee.getPhone().equals(referrer.getPhone())) {
            throw new BizException(ErrorCode.OPERATION_FAILED, "邀请人与您手机号相同，无法绑定");
        }
        // 绑定窗口：注册后 N 天内有效，避免老会员被临时拉去绑定
        if (cfg.bindWindowDays() > 0 && invitee.getCreatedAt() != null) {
            LocalDateTime deadline = invitee.getCreatedAt().plusDays(cfg.bindWindowDays());
            if (LocalDateTime.now().isAfter(deadline)) {
                throw new BizException(ErrorCode.OPERATION_FAILED,
                        "已超过绑定期限（注册后 " + cfg.bindWindowDays() + " 天内可绑定）");
            }
        }
        // 推荐人必须是「老会员」：注册时间不晚于被推荐人
        if (referrer.getCreatedAt() != null && invitee.getCreatedAt() != null
                && referrer.getCreatedAt().isAfter(invitee.getCreatedAt())) {
            throw new BizException(ErrorCode.OPERATION_FAILED, "邀请人必须是早于您注册的会员");
        }
    }

    /* ==================== 3. 结算 ==================== */

    /**
     * 被推荐人达成条件时结算（由消费完成事件触发，也可由定时任务兜底调用）
     *
     * @param inviteeId  被推荐人
     * @param paidAmount 本次实付金额
     * @param bizNo      业务单号
     * @param orderType  订单类型（{@link Order#TYPE_CONSUME} 等）
     * @return 本次是否真的完成了结算
     */
    public boolean settleIfEligible(Long inviteeId, BigDecimal paidAmount, String bizNo, Integer orderType) {
        Referral referral = referralMapper.selectByInvitee(inviteeId);
        if (referral == null || referral.getStatus() == null || referral.getStatus() != Referral.ST_BOUND) {
            return false;
        }
        ReferralConfigService.Cfg cfg = configService.load();
        if (!cfg.enabled()) {
            log.debug("[推荐] 活动已关闭，跳过结算 inviteeId={}", inviteeId);
            return false;
        }
        String mode = cfg.settleMode();
        if ("REGISTER".equals(mode)) {
            // 注册即奖模式下绑定时就已结算；若因异常漏结算，这里补上
            return settle(referral, "REGISTER:" + referral.getId());
        }
        boolean consumeMode = "FIRST_CONSUME".equals(mode);
        if (consumeMode && !Integer.valueOf(Order.TYPE_CONSUME).equals(orderType)) {
            return false;
        }
        if (!consumeMode && !Integer.valueOf(Order.TYPE_RECHARGE).equals(orderType)) {
            return false;
        }
        if (paidAmount == null || paidAmount.compareTo(cfg.minAmount()) < 0) {
            return false;
        }
        // 「首单」判定：必须先确认这是该会员第一笔已支付订单
        if (!isFirstPaidOrder(inviteeId, orderType)) {
            return false;
        }
        return settle(referral, bizNo);
    }

    /**
     * 执行结算：抢占结算权 → 发奖 → 通知
     *
     * <p>刻意<b>不加 @Transactional</b>：结算标记是一条带条件的 UPDATE（原子），
     * 抢到之后就不能回退；发奖在自己的事务里；通知放在最后，失败也不影响奖励。
     */
    public boolean settle(Referral referral, String bizNo) {
        LocalDateTime now = LocalDateTime.now();
        if (referralMapper.markSettled(referral.getId(), now, bizNo) == 0) {
            log.debug("[推荐] 结算权已被占用，跳过 referralId={}", referral.getId());
            return false;
        }
        referral.setStatus(Referral.ST_SETTLED);
        referral.setSettleAt(now);
        referral.setTriggerBizNo(bizNo);
        log.info("[推荐] 结算 referralId={}, referrerId={}, inviteeId={}, bizNo={}",
                referral.getId(), referral.getReferrerId(), referral.getInviteeId(), bizNo);

        try {
            issueRewards(referral, bizNo);
        } catch (Exception e) {
            // 奖励记录已落库（状态为待发放/发放失败），由补偿任务重试，这里只记日志
            log.error("[推荐] 发奖异常，已交由补偿任务处理 referralId={}", referral.getId(), e);
        }
        notifyRewards(referral);
        return true;
    }

    /**
     * 生成并发放两侧奖励
     *
     * <p>月度上限只限制<b>推荐人</b>侧：超限时记一条「发放失败」说明原因，
     * 被推荐人的奖励照发——新人不应为推荐人的刷单行为买单。
     */
    @Transactional(rollbackFor = Exception.class)
    public void issueRewards(Referral referral, String bizNo) {
        ReferralConfigService.Cfg cfg = configService.load();

        if (!"NONE".equals(cfg.referrerType())) {
            boolean overLimit = false;
            if (cfg.maxPerReferrerMonth() > 0) {
                LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();
                int settled = referralMapper.countSettledThisMonth(referral.getReferrerId(), monthStart);
                if (settled > cfg.maxPerReferrerMonth()) {
                    overLimit = true;
                    log.warn("[推荐] 推荐人 {} 本月已结算 {} 单，超过上限 {}，本次不再发放推荐人奖励",
                            referral.getReferrerId(), settled, cfg.maxPerReferrerMonth());
                }
            }
            if (overLimit) {
                recordSkipped(referral, ReferralReward.TARGET_REFERRER, referral.getReferrerId(),
                        cfg.referrerType(), cfg.referrerValue(), cfg.referrerCouponTplId(), bizNo,
                        "超出推荐人每月奖励上限 " + cfg.maxPerReferrerMonth() + " 单");
            } else {
                issueOne(referral, ReferralReward.TARGET_REFERRER, referral.getReferrerId(),
                        cfg.referrerType(), cfg.referrerValue(), cfg.referrerCouponTplId(), bizNo);
            }
        }

        if (!"NONE".equals(cfg.inviteeType())) {
            issueOne(referral, ReferralReward.TARGET_INVITEE, referral.getInviteeId(),
                    cfg.inviteeType(), cfg.inviteeValue(), cfg.inviteeCouponTplId(), bizNo);
        }
    }

    /** 超限/无效奖励：留痕但不入账，运营可据此向会员解释 */
    private void recordSkipped(Referral referral, int target, Long memberId, String type,
                               BigDecimal value, Long couponTplId, String bizNo, String reason) {
        if (rewardMapper.countByReferralAndTarget(referral.getId(), target) > 0) {
            return;
        }
        ReferralReward rw = new ReferralReward();
        rw.setReferralId(referral.getId());
        rw.setRewardTarget(target);
        rw.setMemberId(memberId);
        rw.setRewardType(typeCode(type));
        rw.setRewardValue(value == null ? BigDecimal.ZERO : value);
        rw.setCouponTplId(couponTplId != null && couponTplId > 0 ? couponTplId : null);
        rw.setStatus(ReferralReward.ST_FAILED);
        rw.setFailReason(reason);
        rw.setBizNo(bizNo);
        rewardMapper.insert(rw);
    }

    /**
     * 发放单侧奖励（幂等）
     *
     * <p>幂等有三层：
     * <ol>
     *   <li>{@code countByReferralAndTarget} 前置检查（同一次结算不会重复建档）</li>
     *   <li>资产服务的幂等键 {@code REF:{referralId}:{target}}（重复调用不会重复加钱）</li>
     *   <li>奖励记录状态机：已发放的记录不再重发</li>
     * </ol>
     */
    @Transactional(rollbackFor = Exception.class)
    public void issueOne(Referral referral, int target, Long memberId, String type,
                         BigDecimal value, Long couponTplId, String bizNo) {
        if (rewardMapper.countByReferralAndTarget(referral.getId(), target) > 0) {
            log.debug("[推荐] 奖励已建档，跳过 referralId={}, target={}", referral.getId(), target);
            return;
        }
        ReferralReward rw = new ReferralReward();
        rw.setReferralId(referral.getId());
        rw.setRewardTarget(target);
        rw.setMemberId(memberId);
        rw.setRewardType(typeCode(type));
        rw.setRewardValue(value == null ? BigDecimal.ZERO : value);
        rw.setCouponTplId(couponTplId != null && couponTplId > 0 ? couponTplId : null);
        rw.setStatus(ReferralReward.ST_PENDING);
        rw.setBizNo(bizNo);
        rewardMapper.insert(rw);
        grant(rw, type);
    }

    /** 真正调资产服务发奖；失败只标记不抛出，等补偿任务重试 */
    private void grant(ReferralReward rw, String type) {
        String idemNo = "REF:" + rw.getReferralId() + ":" + rw.getRewardTarget();
        String remark = "推荐有礼-" + ReferralReward.targetText(rw.getRewardTarget());
        try {
            Long couponId = null;
            switch (type) {
                case "POINTS" -> {
                    long points = toLong(rw.getRewardValue());
                    if (points <= 0) {
                        throw new BizException(ErrorCode.PARAM_INVALID, "积分奖励值必须大于 0");
                    }
                    accountService.grantPoints(rw.getMemberId(), points,
                            AccountService.PBIZ_REFERRAL, idemNo, remark, null);
                }
                case "GROWTH" -> {
                    long growth = toLong(rw.getRewardValue());
                    if (growth <= 0) {
                        throw new BizException(ErrorCode.PARAM_INVALID, "成长值奖励必须大于 0");
                    }
                    accountService.change(AccountChangeCmd.builder()
                            .memberId(rw.getMemberId())
                            .bizType(AccountService.GBIZ_REFERRAL)
                            .bizNo(idemNo)
                            .idempotencyKey(idemNo)
                            .growthDelta(growth)
                            .remark(remark)
                            .build());
                }
                case "BALANCE" -> {
                    BigDecimal amount = rw.getRewardValue();
                    if (amount == null || amount.signum() <= 0) {
                        throw new BizException(ErrorCode.PARAM_INVALID, "余额奖励必须大于 0");
                    }
                    accountService.change(AccountChangeCmd.builder()
                            .memberId(rw.getMemberId())
                            .bizType(AccountService.BIZ_GIFT)
                            .bizNo(idemNo)
                            .idempotencyKey(idemNo)
                            .balanceDelta(amount)
                            .remark(remark)
                            .build());
                }
                case "GIFT_BALANCE" -> {
                    BigDecimal amount = rw.getRewardValue();
                    if (amount == null || amount.signum() <= 0) {
                        throw new BizException(ErrorCode.PARAM_INVALID, "赠送余额奖励必须大于 0");
                    }
                    accountService.change(AccountChangeCmd.builder()
                            .memberId(rw.getMemberId())
                            .bizType(AccountService.BIZ_GIFT)
                            .bizNo(idemNo)
                            .idempotencyKey(idemNo)
                            .giftDelta(amount)
                            .remark(remark)
                            .build());
                }
                case "COUPON" -> {
                    if (rw.getCouponTplId() == null) {
                        throw new BizException(ErrorCode.PARAM_INVALID, "券奖励未配置券模板 ID");
                    }
                    List<MemberCoupon> coupons = couponService.grantForce(
                            rw.getMemberId(), rw.getCouponTplId(), 1, "REFERRAL", idemNo);
                    if (coupons.isEmpty()) {
                        throw new BizException(ErrorCode.COUPON_UNAVAILABLE, "券模板发行量已满，暂无法发放");
                    }
                    couponId = coupons.get(0).getId();
                }
                default -> throw new BizException(ErrorCode.PARAM_INVALID, "未知奖励类型：" + type);
            }
            rewardMapper.markIssued(rw.getId(), LocalDateTime.now(), couponId);
            rw.setStatus(ReferralReward.ST_ISSUED);
            rw.setCouponId(couponId);
            rw.setIssuedAt(LocalDateTime.now());
        } catch (Exception e) {
            String reason = clip(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 255);
            rewardMapper.markFailed(rw.getId(), reason);
            rw.setStatus(ReferralReward.ST_FAILED);
            rw.setFailReason(reason);
            log.warn("[推荐] 奖励发放失败 rewardId={}, memberId={}, type={}, 原因={}",
                    rw.getId(), rw.getMemberId(), type, reason);
        }
    }

    /** 发奖后给双方发通知（在结算事务之外，失败不影响奖励） */
    private void notifyRewards(Referral referral) {
        List<ReferralReward> rewards = rewardMapper.selectList(new LambdaQueryWrapper<ReferralReward>()
                .eq(ReferralReward::getReferralId, referral.getId())
                .eq(ReferralReward::getStatus, ReferralReward.ST_ISSUED));
        for (ReferralReward rw : rewards) {
            Map<String, Object> vars = new HashMap<>();
            vars.put("reward", describeReward(rw));
            messageService.sendQuietly("REFERRAL_REWARD", rw.getMemberId(), vars,
                    rw.getBizNo(), "REFERRAL_REWARD:" + referral.getId() + ":" + rw.getRewardTarget());
        }
    }

    private String describeReward(ReferralReward rw) {
        String typeText = ReferralReward.typeText(rw.getRewardType());
        BigDecimal v = rw.getRewardValue() == null ? BigDecimal.ZERO : rw.getRewardValue();
        return switch (rw.getRewardType() == null ? -1 : rw.getRewardType()) {
            case ReferralReward.TYPE_COUPON -> "优惠券 1 张";
            case ReferralReward.TYPE_POINTS, ReferralReward.TYPE_GROWTH -> typeText + " " + toLong(v);
            default -> typeText + " " + v.stripTrailingZeros().toPlainString() + " 元";
        };
    }

    /* ==================== 4. 补偿任务 ==================== */

    /**
     * 重试发放失败/待发放的奖励
     *
     * @return 处理条数
     */
    public int retryPendingRewards(int limit) {
        List<ReferralReward> list = rewardMapper.selectIssuable(LocalDateTime.now().minusDays(30), limit);
        if (list.isEmpty()) {
            return 0;
        }
        ReferralConfigService.Cfg cfg = configService.load();
        int done = 0;
        for (ReferralReward rw : list) {
            String type = typeName(rw.getRewardType());
            if (cfg == null || "NONE".equals(type)) {
                continue;
            }
            grant(rw, type);
            done++;
        }
        return done;
    }

    /**
     * 对账：已结算但奖励记录缺失的关系补建
     *
     * <p>覆盖「markSettled 成功但进程在 issueRewards 前崩溃」这种极端窗口。
     *
     * @return 补建条数
     */
    public int reconcileSettled(int limit) {
        List<Referral> list = referralMapper.selectRecentSettled(LocalDateTime.now().minusDays(30), limit);
        int fixed = 0;
        for (Referral r : list) {
            for (int target : new int[]{ReferralReward.TARGET_REFERRER, ReferralReward.TARGET_INVITEE}) {
                if (rewardMapper.countByReferralAndTarget(r.getId(), target) > 0) {
                    continue;
                }
                log.warn("[推荐] 发现已结算但奖励缺失 referralId={}, target={}，补建", r.getId(), target);
                try {
                    issueRewards(r, r.getTriggerBizNo());
                } catch (Exception e) {
                    log.error("[推荐] 补建奖励失败 referralId={}", r.getId(), e);
                }
                fixed++;
                break;
            }
        }
        return fixed;
    }

    /**
     * 兜底结算：扫「已绑定未结算」的关系，用订单数据反推是否已达成条件
     *
     * <p>用于消费事件丢失（进程重启、事件监听异常）时的自愈。
     *
     * @return 本次结算条数
     */
    public int settleByScan(int limit) {
        ReferralConfigService.Cfg cfg = configService.load();
        if (!cfg.enabled()) {
            return 0;
        }
        boolean consumeMode = !"FIRST_RECHARGE".equals(cfg.settleMode());
        int orderType = consumeMode ? Order.TYPE_CONSUME : Order.TYPE_RECHARGE;
        List<Referral> pending = referralMapper.selectPending(limit);
        int settled = 0;
        for (Referral r : pending) {
            try {
                if ("REGISTER".equals(cfg.settleMode())) {
                    if (settle(r, "REGISTER:" + r.getId())) {
                        settled++;
                    }
                    continue;
                }
                int paid = referralMapper.countPaidOrders(r.getInviteeId(), orderType);
                if (paid == 0) {
                    continue;
                }
                BigDecimal firstAmount = referralMapper.selectFirstPaidAmount(r.getInviteeId(), orderType);
                if (firstAmount == null || firstAmount.compareTo(cfg.minAmount()) < 0) {
                    continue;
                }
                if (settle(r, "SCAN:" + r.getId())) {
                    settled++;
                }
            } catch (Exception e) {
                log.warn("[推荐] 兜底结算失败 referralId={}, 原因={}", r.getId(), e.getMessage());
            }
        }
        return settled;
    }

    /* ==================== 5. 会员端 ==================== */

    /** 小程序「推荐有礼」页数据 */
    public Map<String, Object> myCenter(Long memberId) {
        ReferralConfigService.Cfg cfg = configService.load();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", cfg.enabled());
        out.put("inviteCode", inviteCode(memberId));
        out.put("settleMode", cfg.settleMode());
        out.put("minAmount", cfg.minAmount());
        out.put("ruleDesc", cfg.ruleDesc());
        out.put("posterUrl", cfg.posterUrl());
        out.put("bindWindowDays", cfg.bindWindowDays());

        Member me = memberMapper.selectById(memberId);
        out.put("bound", me != null && me.getReferrerId() != null);

        out.put("inviteCount", referralMapper.countBound(memberId));
        out.put("settledCount", referralMapper.countByReferrerAndStatus(memberId, Referral.ST_SETTLED));

        BigDecimal points = rewardMapper.sumReward(memberId, ReferralReward.TYPE_POINTS,
                ReferralReward.TARGET_REFERRER);
        BigDecimal balance = rewardMapper.sumReward(memberId, ReferralReward.TYPE_BALANCE,
                ReferralReward.TARGET_REFERRER);
        out.put("totalPoints", points == null ? BigDecimal.ZERO : points);
        out.put("totalBalance", balance == null ? BigDecimal.ZERO : balance);

        // 我的奖励明细（最近 20 条）
        List<ReferralReward> rewards = rewardMapper.selectList(new LambdaQueryWrapper<ReferralReward>()
                .eq(ReferralReward::getMemberId, memberId)
                .orderByDesc(ReferralReward::getId)
                .last("LIMIT 20"));
        List<Map<String, Object>> rewardVos = new ArrayList<>();
        for (ReferralReward rw : rewards) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rw.getId());
            m.put("target", rw.getRewardTarget());
            m.put("targetText", ReferralReward.targetText(rw.getRewardTarget()));
            m.put("rewardType", rw.getRewardType());
            m.put("rewardTypeText", ReferralReward.typeText(rw.getRewardType()));
            m.put("rewardValue", rw.getRewardValue());
            m.put("status", rw.getStatus());
            m.put("statusText", ReferralReward.statusText(rw.getStatus()));
            m.put("createdAt", rw.getCreatedAt());
            rewardVos.add(m);
        }
        out.put("rewards", rewardVos);

        // 我邀请的人（脱敏展示）
        List<Referral> mine = referralMapper.selectList(new LambdaQueryWrapper<Referral>()
                .eq(Referral::getReferrerId, memberId)
                .orderByDesc(Referral::getId)
                .last("LIMIT 50"));
        List<Map<String, Object>> invitees = new ArrayList<>();
        if (!mine.isEmpty()) {
            List<Long> ids = mine.stream().map(Referral::getInviteeId).toList();
            Map<Long, Member> members = new HashMap<>();
            memberMapper.selectBatchIds(ids).forEach(m -> members.put(m.getId(), m));
            for (Referral r : mine) {
                Member inv = members.get(r.getInviteeId());
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("nickname", inv == null ? "好友" : maskName(inv));
                m.put("phone", inv == null || inv.getPhone() == null ? "" : MaskUtil.phone(inv.getPhone()));
                m.put("bindAt", r.getBindAt());
                m.put("status", r.getStatus());
                m.put("statusText", Referral.statusText(r.getStatus()));
                invitees.add(m);
            }
        }
        out.put("invitees", invitees);
        return out;
    }

    /* ==================== 6. 管理端 ==================== */

    public PageResult<Map<String, Object>> pageReferrals(Long referrerId, Integer status,
                                                         int pageNum, int pageSize) {
        LambdaQueryWrapper<Referral> q = new LambdaQueryWrapper<Referral>()
                .eq(referrerId != null, Referral::getReferrerId, referrerId)
                .eq(status != null, Referral::getStatus, status)
                .orderByDesc(Referral::getId);
        Page<Referral> page = referralMapper.selectPage(new Page<>(pageNum, pageSize), q);
        return PageResult.of(page, this::referralVo);
    }

    public PageResult<Map<String, Object>> pageRewards(Long memberId, Integer status, Integer target,
                                                       int pageNum, int pageSize) {
        LambdaQueryWrapper<ReferralReward> q = new LambdaQueryWrapper<ReferralReward>()
                .eq(memberId != null, ReferralReward::getMemberId, memberId)
                .eq(status != null, ReferralReward::getStatus, status)
                .eq(target != null, ReferralReward::getRewardTarget, target)
                .orderByDesc(ReferralReward::getId);
        Page<ReferralReward> page = rewardMapper.selectPage(new Page<>(pageNum, pageSize), q);
        return PageResult.of(page, this::rewardVo);
    }

    /** 运营概览：绑定/结算/转化率/奖励汇总 */
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        long bound = referralMapper.selectCount(new LambdaQueryWrapper<Referral>()
                .eq(Referral::getStatus, Referral.ST_BOUND));
        long settled = referralMapper.selectCount(new LambdaQueryWrapper<Referral>()
                .eq(Referral::getStatus, Referral.ST_SETTLED));
        long invalid = referralMapper.selectCount(new LambdaQueryWrapper<Referral>()
                .eq(Referral::getStatus, Referral.ST_INVALID));
        out.put("bound", bound);
        out.put("settled", settled);
        out.put("invalid", invalid);
        long total = bound + settled + invalid;
        out.put("total", total);
        out.put("settleRate", total == 0 ? 0 : Math.round(settled * 10000.0 / total) / 100.0);

        String monthStart = LocalDate.now().format(MONTH_FMT) + "-01 00:00:00";
        List<Map<String, Object>> byType = new ArrayList<>();
        for (int type : new int[]{ReferralReward.TYPE_POINTS, ReferralReward.TYPE_BALANCE,
                ReferralReward.TYPE_GIFT_BALANCE, ReferralReward.TYPE_GROWTH, ReferralReward.TYPE_COUPON}) {
            Long cnt = rewardMapper.selectCount(new LambdaQueryWrapper<ReferralReward>()
                    .eq(ReferralReward::getRewardType, type)
                    .eq(ReferralReward::getStatus, ReferralReward.ST_ISSUED));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type);
            m.put("typeText", ReferralReward.typeText(type));
            m.put("count", cnt);
            byType.add(m);
        }
        out.put("rewardByType", byType);
        out.put("failedRewards", rewardMapper.selectCount(new LambdaQueryWrapper<ReferralReward>()
                .eq(ReferralReward::getStatus, ReferralReward.ST_FAILED)));
        out.put("config", configService.describe());
        out.put("monthStart", monthStart);
        return out;
    }

    /** 人工结算（补偿卡住的推荐关系，奖励照常发放） */
    public void manualSettle(Long referralId, String reason) {
        Referral r = referralMapper.selectById(referralId);
        if (r == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "推荐关系不存在");
        }
        if (r.getStatus() != null && r.getStatus() == Referral.ST_SETTLED) {
            // 已结算但奖励缺失时允许补发
            try {
                issueRewards(r, r.getTriggerBizNo());
            } catch (Exception e) {
                throw new BizException(ErrorCode.OPERATION_FAILED, "补发奖励失败：" + e.getMessage());
            }
            return;
        }
        if (!settle(r, "MANUAL:" + referralId + ":" + (reason == null ? "" : clip(reason, 20)))) {
            throw new BizException(ErrorCode.OPERATION_FAILED, "结算失败，该关系可能已失效");
        }
    }

    /** 作废推荐关系（风控发现刷单时使用） */
    @Transactional(rollbackFor = Exception.class)
    public void invalidate(Long referralId, String reason) {
        Referral r = referralMapper.selectById(referralId);
        if (r == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "推荐关系不存在");
        }
        if (r.getStatus() != null && r.getStatus() == Referral.ST_SETTLED) {
            throw new BizException(ErrorCode.OPERATION_FAILED, "已结算的推荐关系不能作废（奖励已入账），请走资产调整冲正");
        }
        if (referralMapper.markInvalid(referralId) == 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED, "该关系状态已变更，请刷新后重试");
        }
        Member patch = new Member();
        patch.setId(r.getInviteeId());
        // MyBatis-Plus 默认忽略 null，用 UpdateWrapper 才能把 referrer_id 置空
        memberMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Member>()
                .eq(Member::getId, r.getInviteeId())
                .set(Member::getReferrerId, null));
        log.info("[推荐] 作废 referralId={}, 原因={}", referralId, reason);
    }

    /* ==================== 7. 转换与工具 ==================== */

    private Map<String, Object> referralVo(Referral r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("referrerId", r.getReferrerId());
        m.put("inviteeId", r.getInviteeId());
        m.put("channel", r.getChannel());
        m.put("status", r.getStatus());
        m.put("statusText", Referral.statusText(r.getStatus()));
        m.put("bindAt", r.getBindAt());
        m.put("settleAt", r.getSettleAt());
        m.put("triggerBizNo", r.getTriggerBizNo());
        m.put("createdAt", r.getCreatedAt());
        m.put("referrer", memberBrief(r.getReferrerId()));
        m.put("invitee", memberBrief(r.getInviteeId()));
        return m;
    }

    private Map<String, Object> memberBrief(Long id) {
        if (id == null) {
            return null;
        }
        Member m = memberMapper.selectById(id);
        if (m == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", m.getId());
        out.put("memberNo", m.getMemberNo());
        out.put("name", maskName(m));
        out.put("phone", m.getPhone() == null ? "" : MaskUtil.phone(m.getPhone()));
        return out;
    }

    private Map<String, Object> rewardVo(ReferralReward rw) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rw.getId());
        m.put("referralId", rw.getReferralId());
        m.put("rewardTarget", rw.getRewardTarget());
        m.put("rewardTargetText", ReferralReward.targetText(rw.getRewardTarget()));
        m.put("memberId", rw.getMemberId());
        m.put("rewardType", rw.getRewardType());
        m.put("rewardTypeText", ReferralReward.typeText(rw.getRewardType()));
        m.put("rewardValue", rw.getRewardValue());
        m.put("couponTplId", rw.getCouponTplId());
        m.put("couponId", rw.getCouponId());
        m.put("status", rw.getStatus());
        m.put("statusText", ReferralReward.statusText(rw.getStatus()));
        m.put("failReason", rw.getFailReason());
        m.put("bizNo", rw.getBizNo());
        m.put("createdAt", rw.getCreatedAt());
        m.put("issuedAt", rw.getIssuedAt());
        m.put("member", memberBrief(rw.getMemberId()));
        return m;
    }

    private static int typeCode(String type) {
        return switch (type == null ? "NONE" : type) {
            case "POINTS" -> ReferralReward.TYPE_POINTS;
            case "BALANCE" -> ReferralReward.TYPE_BALANCE;
            case "COUPON" -> ReferralReward.TYPE_COUPON;
            case "GROWTH" -> ReferralReward.TYPE_GROWTH;
            case "GIFT_BALANCE" -> ReferralReward.TYPE_GIFT_BALANCE;
            default -> 0;
        };
    }

    private static String typeName(Integer code) {
        if (code == null) {
            return "NONE";
        }
        return switch (code) {
            case ReferralReward.TYPE_POINTS -> "POINTS";
            case ReferralReward.TYPE_BALANCE -> "BALANCE";
            case ReferralReward.TYPE_COUPON -> "COUPON";
            case ReferralReward.TYPE_GROWTH -> "GROWTH";
            case ReferralReward.TYPE_GIFT_BALANCE -> "GIFT_BALANCE";
            default -> "NONE";
        };
    }

    private boolean isFirstPaidOrder(Long memberId, Integer orderType) {
        return referralMapper.countPaidOrders(memberId, orderType) == 1;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String maskName(Member m) {
        String name = m.getRealName() != null && !m.getRealName().isBlank()
                ? m.getRealName() : m.getNickname();
        if (name == null || name.isBlank()) {
            return "会员" + (m.getMemberNo() == null ? "" : m.getMemberNo());
        }
        return MaskUtil.name(name);
    }

    private static long toLong(BigDecimal v) {
        return v == null ? 0L : v.longValue();
    }

    private static String clip(String v, int max) {
        if (v == null) {
            return null;
        }
        return v.length() <= max ? v : v.substring(0, max);
    }
}
