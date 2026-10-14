package com.company.member.modules.birthday.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.member.common.exception.BizException;
import com.company.member.common.result.ErrorCode;
import com.company.member.common.result.PageResult;
import com.company.member.common.util.MaskUtil;
import com.company.member.modules.account.dto.AccountChangeCmd;
import com.company.member.modules.account.service.AccountService;
import com.company.member.modules.birthday.entity.BirthdayGiftLog;
import com.company.member.modules.birthday.mapper.BirthdayGiftLogMapper;
import com.company.member.modules.marketing.entity.CouponTpl;
import com.company.member.modules.marketing.entity.MemberCoupon;
import com.company.member.modules.marketing.mapper.CouponTplMapper;
import com.company.member.modules.marketing.service.CouponService;
import com.company.member.modules.member.entity.Member;
import com.company.member.modules.member.mapper.MemberMapper;
import com.company.member.modules.message.service.MessageService;
import com.company.member.modules.system.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生日礼
 *
 * <p><b>为什么要单独一张发放日志表</b>：生日礼是「按年一次性」的事件，
 * 定时任务可能因为重启、补跑、多实例并发而重复执行。仅靠资产层的幂等键不够——
 * 资产层只能保证「不重复加钱」，不能保证「不重复发券」「不重复发消息」，
 * 也无法回答运营的问题「这个会员今年到底领没领、领了什么」。
 * 因此用 (member_id, gift_year) 唯一索引作为唯一的真相来源。
 *
 * <p><b>年份语义</b>：{@code gift_year} 取「这次生日礼对应的那个生日的年份」。
 * 若配置为提前 7 天发放，12 月 30 日的会员会在次年 1 月 6 日收到礼，
 * 此时归入次年——与会员「我今年收到过生日礼了」的认知一致。
 *
 * <p><b>部分失败可修复</b>：先落一条「部分发放」的占位记录再逐项发放，
 * 任一项失败都留在记录里；{@link #repairFailed} 会重跑未完成的项，
 * 而资产层的幂等键保证已成功的项不会被加第二次。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BirthdayGiftService {

    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final BirthdayGiftLogMapper logMapper;
    private final MemberMapper memberMapper;
    private final CouponTplMapper couponTplMapper;
    private final CouponService couponService;
    private final AccountService accountService;
    private final MessageService messageService;
    private final ConfigService configService;

    /* ==================== 1. 配置 ==================== */

    /**
     * 生日礼配置快照
     *
     * @param enabled       总开关
     * @param daysBefore    提前天数，0 表示生日当天
     * @param points        赠送积分（0 表示不送）
     * @param balance       赠送余额（元，0 表示不送）
     * @param growth        赠送成长值（0 表示不送）
     * @param couponTplId   赠送券模板 id（0 表示不送）
     * @param notifyEnabled 是否发送生日祝福消息
     * @param wishText      祝福文案
     */
    public record Cfg(boolean enabled, int daysBefore, long points, BigDecimal balance, long growth,
                      Long couponTplId, boolean notifyEnabled, String wishText) {
    }

    public Cfg load() {
        return new Cfg(
                configService.getBoolean("birthday.enabled", false),
                configService.getInt("birthday.gift-days-before", 0),
                configService.getLong("birthday.gift-points", 0L),
                configService.getDecimal("birthday.gift-balance", BigDecimal.ZERO),
                configService.getLong("birthday.gift-growth", 0L),
                configService.getLong("birthday.gift-coupon-tpl-id", 0L),
                configService.getBoolean("birthday.notify-enabled", true),
                configService.getString("birthday.wish-text",
                        "亲爱的{memberName}，祝您生日快乐！专属生日礼已放入您的卡包，请查收。"));
    }

    public List<Map<String, Object>> describe() {
        Cfg c = load();
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(item("birthday.enabled", "生日礼开关", c.enabled(), "BOOLEAN",
                "关闭后定时任务不再发放", null));
        out.add(item("birthday.gift-days-before", "提前天数", c.daysBefore(), "NUMBER",
                "0 表示生日当天发放；建议 0~7 天", null));
        out.add(item("birthday.gift-points", "赠送积分", c.points(), "NUMBER",
                "0 表示不赠送积分", null));
        out.add(item("birthday.gift-balance", "赠送余额(元)", c.balance(), "DECIMAL",
                "计入赠送余额，不可退；0 表示不赠送", null));
        out.add(item("birthday.gift-growth", "赠送成长值", c.growth(), "NUMBER",
                "0 表示不赠送", null));
        out.add(item("birthday.gift-coupon-tpl-id", "赠送券模板ID", c.couponTplId(), "NUMBER",
                "0 表示不赠券；默认使用 BIRTHDAY_50 模板", null));
        out.add(item("birthday.notify-enabled", "发送祝福消息", c.notifyEnabled(), "BOOLEAN",
                "关闭后仍发礼但不发消息", null));
        out.add(item("birthday.wish-text", "祝福文案", c.wishText(), "TEXT",
                "支持 {memberName} 等占位符", null));
        return out;
    }

    public void update(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        Map<String, String> cleaned = new LinkedHashMap<>();
        values.forEach((k, v) -> {
            if (k != null && v != null) {
                cleaned.put(k, v.trim());
            }
        });
        checkInt(cleaned, "birthday.gift-days-before", 0, 90);
        checkLong(cleaned, "birthday.gift-points", 0);
        checkLong(cleaned, "birthday.gift-growth", 0);
        checkDecimal(cleaned, "birthday.gift-balance");
        checkLong(cleaned, "birthday.gift-coupon-tpl-id", 0);

        // 券模板必须真实存在且启用，否则发放时才失败会让会员收到「空生日礼」
        String tplId = cleaned.get("birthday.gift-coupon-tpl-id");
        if (tplId == null) {
            tplId = configService.getString("birthday.gift-coupon-tpl-id", "0");
        }
        long tpl = parseLong(tplId, 0);
        if (tpl > 0) {
            CouponTpl t = couponTplMapper.selectById(tpl);
            if (t == null || t.getStatus() == null || t.getStatus() != 1) {
                throw new BizException(ErrorCode.PARAM_INVALID, "生日券模板不存在或已停用：id=" + tpl);
            }
        }
        configService.updateBatch(cleaned);
        log.info("[生日礼] 配置已更新，共 {} 项", cleaned.size());
    }

    /* ==================== 2. 批量发放 ==================== */

    /**
     * 扫描并按配置发放生日礼（定时任务入口）
     *
     * @param limit 每个「月-日」分组最多处理的会员数
     * @return 实际发放人数
     */
    public int issueBatch(int limit) {
        Cfg cfg = load();
        if (!cfg.enabled()) {
            log.debug("[生日礼] 已关闭，跳过");
            return 0;
        }
        LocalDate target = LocalDate.now().plusDays(cfg.daysBefore());
        int year = target.getYear();
        int issued = 0;
        for (String md : candidateMonthDays(target)) {
            issued += issueGroup(md, target, year, limit);
        }
        if (issued > 0) {
            log.info("[生日礼] 本次发放 {} 人（对应生日 {}）", issued, target.format(YMD));
        }
        return issued;
    }

    private int issueGroup(String monthDay, LocalDate target, int year, int limit) {
        long cursor = 0;
        int issued = 0;
        while (issued < limit) {
            int pageSize = Math.min(200, limit - issued);
            List<Member> batch = memberMapper.selectByBirthdayMonthDay(monthDay, cursor, pageSize);
            if (batch.isEmpty()) {
                break;
            }
            for (Member m : batch) {
                cursor = m.getId();
                try {
                    if (issueOne(m, target, year, false) != null) {
                        issued++;
                    }
                } catch (Exception e) {
                    log.warn("[生日礼] 发放异常 memberId={}, 原因={}", m.getId(), e.getMessage());
                }
            }
            if (batch.size() < pageSize) {
                break;
            }
        }
        return issued;
    }

    /**
     * 需要扫描的「月-日」集合
     *
     * <p>2 月 29 日出生的人在平年没有生日。若目标日是 2 月 28 日且当年不是闰年，
     * 额外扫描 02-29，让这些人也能在 2 月 28 日收到生日礼，而不是被系统遗忘。
     */
    static List<String> candidateMonthDays(LocalDate target) {
        List<String> list = new ArrayList<>();
        list.add(target.format(MD));
        if (target.getMonthValue() == 2 && target.getDayOfMonth() == 28 && !target.isLeapYear()) {
            list.add("02-29");
        }
        return list;
    }

    /* ==================== 3. 单人发放 ==================== */

    /**
     * 给一个会员发放生日礼（幂等）
     *
     * @param member   会员
     * @param birthday 本次生日对应的日期（可能因提前发放而跨年）
     * @param year     归属年份
     * @param force    管理端手工补发时为 true，会绕过「已发放」判断重新尝试缺失项
     * @return 发放记录；若本年度已完整发放过则返回 null
     */
    public BirthdayGiftLog issueOne(Member member, LocalDate birthday, int year, boolean force) {
        if (member == null) {
            return null;
        }
        Cfg cfg = load();
        if (!force && !cfg.enabled()) {
            return null;
        }

        BirthdayGiftLog record = logMapper.selectByMemberYear(member.getId(), year);
        if (record != null && record.getStatus() != null
                && record.getStatus() == BirthdayGiftLog.ST_ISSUED && !force) {
            return null;
        }
        if (record == null) {
            BirthdayGiftLog fresh = new BirthdayGiftLog();
            fresh.setMemberId(member.getId());
            fresh.setGiftYear(year);
            fresh.setGiftDate(birthday);
            fresh.setLevelId(member.getLevelId());
            // 先落「部分发放」占位：唯一索引挡住重复，进程中断后也能被 repairFailed 捞起
            fresh.setStatus(BirthdayGiftLog.ST_PARTIAL);
            fresh.setPoints(0L);
            fresh.setBalance(BigDecimal.ZERO);
            fresh.setGrowth(0L);
            fresh.setMessageSent(0);
            try {
                logMapper.insert(fresh);
                record = fresh;
            } catch (DuplicateKeyException e) {
                record = logMapper.selectByMemberYear(member.getId(), year);
                if (record == null) {
                    throw e;
                }
                if (record.getStatus() != null && record.getStatus() == BirthdayGiftLog.ST_ISSUED && !force) {
                    return null;
                }
            }
        }

        grant(record, member, cfg);
        return record;
    }

    /** 逐项发放并记录结果（每项都靠资产层幂等键保证不重复入账） */
    private void grant(BirthdayGiftLog record, Member member, Cfg cfg) {
        String bizNo = businessNo(member.getId(), record.getGiftYear());
        String remark = "生日礼 " + record.getGiftYear() + " 年度";
        List<String> failures = new ArrayList<>();
        List<String> successes = new ArrayList<>();

        long points = cfg.points();
        if (points > 0) {
            try {
                accountService.grantPoints(member.getId(), points,
                        AccountService.PBIZ_BIRTHDAY, bizNo, remark, null);
                record.setPoints(points);
                successes.add("积分 " + points);
            } catch (Exception e) {
                failures.add("积分：" + msg(e));
            }
        }
        BigDecimal balance = cfg.balance();
        if (balance != null && balance.signum() > 0) {
            try {
                accountService.change(AccountChangeCmd.builder()
                        .memberId(member.getId())
                        .bizType(AccountService.BIZ_GIFT)
                        .bizNo(bizNo)
                        .idempotencyKey(bizNo)
                        .giftDelta(balance)
                        .remark(remark)
                        .build());
                record.setBalance(balance);
                successes.add("赠送余额 " + balance.toPlainString());
            } catch (Exception e) {
                failures.add("赠送余额：" + msg(e));
            }
        }
        long growth = cfg.growth();
        if (growth > 0) {
            try {
                accountService.change(AccountChangeCmd.builder()
                        .memberId(member.getId())
                        .bizType(AccountService.GBIZ_BIRTHDAY)
                        .bizNo(bizNo)
                        .idempotencyKey(bizNo)
                        .growthDelta(growth)
                        .remark(remark)
                        .build());
                record.setGrowth(growth);
                successes.add("成长值 " + growth);
            } catch (Exception e) {
                failures.add("成长值：" + msg(e));
            }
        }
        Long tplId = cfg.couponTplId();
        if (tplId != null && tplId > 0) {
            try {
                List<MemberCoupon> coupons = couponService.grantForce(
                        member.getId(), tplId, 1, "BIRTHDAY", bizNo);
                if (coupons.isEmpty()) {
                    failures.add("优惠券：模板发行量已满");
                } else {
                    record.setCouponTplId(tplId);
                    record.setCouponId(coupons.get(0).getId());
                    successes.add("优惠券 " + coupons.get(0).getCouponName());
                }
            } catch (Exception e) {
                failures.add("优惠券：" + msg(e));
            }
        }

        if (failures.isEmpty()) {
            record.setStatus(BirthdayGiftLog.ST_ISSUED);
            record.setFailReason(null);
        } else if (successes.isEmpty()) {
            record.setStatus(BirthdayGiftLog.ST_FAILED);
            record.setFailReason(clip(String.join("；", failures), 255));
        } else {
            record.setStatus(BirthdayGiftLog.ST_PARTIAL);
            record.setFailReason(clip(String.join("；", failures), 255));
        }
        logMapper.updateById(record);

        if (record.getStatus() != BirthdayGiftLog.ST_FAILED) {
            log.info("[生日礼] 会员 {} 年度 {} 发放完成：{}", member.getId(), record.getGiftYear(),
                    successes.isEmpty() ? "无礼品项" : String.join("、", successes));
            notifyMember(record, member, cfg);
        } else {
            log.warn("[生日礼] 会员 {} 年度 {} 发放失败：{}", member.getId(), record.getGiftYear(),
                    record.getFailReason());
        }
    }

    private void notifyMember(BirthdayGiftLog record, Member member, Cfg cfg) {
        if (!cfg.notifyEnabled() || record.getMessageSent() != null && record.getMessageSent() == 1) {
            return;
        }
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("memberName", member.getRealName() != null && !member.getRealName().isBlank()
                ? member.getRealName() : "亲爱的会员");
        vars.put("wish", cfg.wishText());
        vars.put("giftDesc", describeGift(record));
        messageService.sendQuietly("BIRTHDAY_GIFT", member.getId(), vars,
                businessNo(member.getId(), record.getGiftYear()),
                "BIRTHDAY_MSG:" + member.getId() + ":" + record.getGiftYear());
        logMapper.markMessageSent(record.getId());
        record.setMessageSent(1);
    }

    private String describeGift(BirthdayGiftLog record) {
        List<String> parts = new ArrayList<>();
        if (record.getPoints() != null && record.getPoints() > 0) {
            parts.add(record.getPoints() + " 积分");
        }
        if (record.getBalance() != null && record.getBalance().signum() > 0) {
            parts.add("赠送余额 " + record.getBalance().stripTrailingZeros().toPlainString() + " 元");
        }
        if (record.getGrowth() != null && record.getGrowth() > 0) {
            parts.add(record.getGrowth() + " 成长值");
        }
        if (record.getCouponId() != null) {
            MemberCoupon c = couponService.listBySourceBizNo(
                            businessNo(record.getMemberId(), record.getGiftYear()))
                    .stream().findFirst().orElse(null);
            parts.add(c == null ? "优惠券 1 张" : c.getCouponName());
        }
        return parts.isEmpty() ? "生日祝福" : String.join("、", parts);
    }

    /* ==================== 4. 补偿 ==================== */

    /**
     * 重发「部分发放 / 发放失败」的生日礼
     *
     * @return 处理条数
     */
    public int repairFailed(int limit) {
        List<BirthdayGiftLog> list = logMapper.selectRepairable(LocalDateTime.now().minusDays(90), limit);
        int done = 0;
        for (BirthdayGiftLog record : list) {
            Member member = memberMapper.selectById(record.getMemberId());
            if (member == null || (member.getDeleted() != null && member.getDeleted() == 1)) {
                continue;
            }
            try {
                grant(record, member, load());
                done++;
            } catch (Exception e) {
                log.warn("[生日礼] 修复失败 logId={}, 原因={}", record.getId(), e.getMessage());
            }
        }
        return done;
    }

    /* ==================== 5. 管理端 ==================== */

    /** 预览某天会发多少人（不实际发放） */
    public Map<String, Object> preview(String monthDay) {
        String md = monthDay == null || monthDay.isBlank()
                ? LocalDate.now().format(MD) : monthDay.trim();
        if (!md.matches("\\d{2}-\\d{2}")) {
            throw new BizException(ErrorCode.PARAM_INVALID, "日期格式应为 MM-dd");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("monthDay", md);
        out.put("count", memberMapper.countByBirthdayMonthDay(md));
        List<Member> sample = memberMapper.selectByBirthdayMonthDay(md, 0, 20);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Member m : sample) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", m.getId());
            item.put("memberNo", m.getMemberNo());
            item.put("name", m.getRealName() != null && !m.getRealName().isBlank()
                    ? MaskUtil.name(m.getRealName()) : "会员");
            item.put("phone", m.getPhone() == null ? "" : MaskUtil.phone(m.getPhone()));
            item.put("birthday", m.getBirthday() == null ? null : m.getBirthday().format(YMD));
            item.put("giftedThisYear", logMapper.selectByMemberYear(m.getId(), LocalDate.now().getYear()) != null);
            list.add(item);
        }
        out.put("sample", list);
        out.put("config", describe());
        out.put("couponTplName", couponName(load().couponTplId()));
        return out;
    }

    public PageResult<Map<String, Object>> pageLogs(Long memberId, Integer status, Integer year,
                                                    int pageNum, int pageSize) {
        LambdaQueryWrapper<BirthdayGiftLog> q = new LambdaQueryWrapper<BirthdayGiftLog>()
                .eq(memberId != null, BirthdayGiftLog::getMemberId, memberId)
                .eq(status != null, BirthdayGiftLog::getStatus, status)
                .eq(year != null, BirthdayGiftLog::getGiftYear, year)
                .orderByDesc(BirthdayGiftLog::getId);
        Page<BirthdayGiftLog> page = logMapper.selectPage(new Page<>(pageNum, pageSize), q);
        // 批量取会员再回填：逐行 selectById 在 20 条/页时就是 20 次查询
        List<Long> ids = page.getRecords().stream().map(BirthdayGiftLog::getMemberId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, Member> members = new java.util.HashMap<>();
        if (!ids.isEmpty()) {
            memberMapper.selectByIds(ids).forEach(m -> members.put(m.getId(), m));
        }
        return PageResult.of(page, l -> toVo(l, members.get(l.getMemberId())));
    }

    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        int year = LocalDate.now().getYear();
        out.put("giftYear", year);
        out.put("issuedThisYear", logMapper.selectCount(new LambdaQueryWrapper<BirthdayGiftLog>()
                .eq(BirthdayGiftLog::getGiftYear, year)
                .eq(BirthdayGiftLog::getStatus, BirthdayGiftLog.ST_ISSUED)));
        out.put("partial", logMapper.selectCount(new LambdaQueryWrapper<BirthdayGiftLog>()
                .eq(BirthdayGiftLog::getStatus, BirthdayGiftLog.ST_PARTIAL)));
        out.put("failed", logMapper.selectCount(new LambdaQueryWrapper<BirthdayGiftLog>()
                .eq(BirthdayGiftLog::getStatus, BirthdayGiftLog.ST_FAILED)));
        out.put("todayCount", memberMapper.countByBirthdayMonthDay(LocalDate.now().format(MD)));
        out.put("config", describe());
        out.put("couponTplName", couponName(load().couponTplId()));
        return out;
    }

    /** 手工补发单个会员（force=true 会重试缺失项） */
    @Transactional(rollbackFor = Exception.class)
    public BirthdayGiftLog manualIssue(Long memberId, Integer year, boolean force) {
        Member member = memberMapper.selectById(memberId);
        if (member == null || (member.getDeleted() != null && member.getDeleted() == 1)) {
            throw new BizException(ErrorCode.MEMBER_NOT_FOUND);
        }
        int y = year == null ? LocalDate.now().getYear() : year;
        LocalDate birthday = member.getBirthday() == null
                ? LocalDate.now()
                : safeBirthday(member.getBirthday(), y);
        BirthdayGiftLog record = issueOne(member, birthday, y, force);
        if (record == null) {
            throw new BizException(ErrorCode.OPERATION_FAILED,
                    "该会员 " + y + " 年度生日礼已完整发放，无需重复发放");
        }
        if (record.getStatus() != null && record.getStatus() == BirthdayGiftLog.ST_FAILED) {
            throw new BizException(ErrorCode.OPERATION_FAILED, "发放失败：" + record.getFailReason());
        }
        return record;
    }

    /* ==================== 6. 会员端 ==================== */

    /** 小程序「我的生日礼」 */
    public Map<String, Object> myBirthday(Long memberId) {
        Cfg cfg = load();
        Member member = memberMapper.selectById(memberId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", cfg.enabled());
        out.put("wishText", cfg.wishText());
        out.put("giftDesc", pendingGiftDesc(cfg));
        out.put("daysBefore", cfg.daysBefore());

        if (member == null || member.getBirthday() == null) {
            out.put("hasBirthday", false);
            out.put("birthday", null);
            out.put("daysUntil", null);
            out.put("giftedThisYear", false);
            out.put("lastGift", null);
            return out;
        }
        LocalDate bd = member.getBirthday();
        out.put("hasBirthday", true);
        out.put("birthday", bd.format(YMD));
        out.put("birthdayMonthDay", bd.format(MD));
        LocalDate today = LocalDate.now();
        out.put("daysUntil", daysUntil(today, MonthDay.of(bd.getMonthValue(), bd.getDayOfMonth())));

        BirthdayGiftLog latest = logMapper.selectLatest(memberId);
        int currentYear = today.plusDays(cfg.daysBefore()).getYear();
        BirthdayGiftLog thisYear = logMapper.selectByMemberYear(memberId, currentYear);
        out.put("giftedThisYear", thisYear != null
                && thisYear.getStatus() != null
                && thisYear.getStatus() != BirthdayGiftLog.ST_FAILED);
        out.put("lastGift", latest == null ? null
                : toVo(latest, latest.getMemberId() == null ? null : memberMapper.selectById(latest.getMemberId())));
        return out;
    }

    /**
     * 距离下一个生日的天数（含 2 月 29 日降级处理）
     *
     * <p>闰日出生的人在平年按 2 月 28 日算，避免算出 366 天的异常值。
     */
    static long daysUntil(LocalDate today, MonthDay monthDay) {
        LocalDate next = monthDay.atYear(today.getYear());
        if (!monthDay.isValidYear(today.getYear())) {
            next = LocalDate.of(today.getYear(), 2, 28);
        }
        if (next.isBefore(today)) {
            int nextYear = today.getYear() + 1;
            next = monthDay.isValidYear(nextYear)
                    ? monthDay.atYear(nextYear)
                    : LocalDate.of(nextYear, 2, 28);
        }
        return ChronoUnit.DAYS.between(today, next);
    }

    private String pendingGiftDesc(Cfg cfg) {
        List<String> parts = new ArrayList<>();
        if (cfg.points() > 0) {
            parts.add(cfg.points() + " 积分");
        }
        if (cfg.balance() != null && cfg.balance().signum() > 0) {
            parts.add("赠送余额 " + cfg.balance().stripTrailingZeros().toPlainString() + " 元");
        }
        if (cfg.growth() > 0) {
            parts.add(cfg.growth() + " 成长值");
        }
        String coupon = couponName(cfg.couponTplId());
        if (coupon != null) {
            parts.add(coupon);
        }
        return parts.isEmpty() ? "生日专属权益" : String.join("、", parts);
    }

    /* ==================== 7. 工具 ==================== */

    /**
     * 生日礼记录 → 展示对象
     *
     * @param member 调用方批量取回的会员；传 null 时按 id 单查（单条场景如「我的生日礼」）
     */
    private Map<String, Object> toVo(BirthdayGiftLog l, Member member) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("memberId", l.getMemberId());
        m.put("giftYear", l.getGiftYear());
        m.put("giftDate", l.getGiftDate() == null ? null : l.getGiftDate().format(YMD));
        m.put("levelId", l.getLevelId());
        m.put("points", l.getPoints());
        m.put("balance", l.getBalance());
        m.put("growth", l.getGrowth());
        m.put("couponTplId", l.getCouponTplId());
        m.put("couponId", l.getCouponId());
        m.put("status", l.getStatus());
        m.put("statusText", BirthdayGiftLog.statusText(l.getStatus()));
        m.put("failReason", l.getFailReason());
        m.put("messageSent", l.getMessageSent());
        m.put("createdAt", l.getCreatedAt());
        if (member != null) {
            Map<String, Object> brief = new LinkedHashMap<>();
            brief.put("id", member.getId());
            brief.put("memberNo", member.getMemberNo());
            brief.put("name", member.getRealName() != null && !member.getRealName().isBlank()
                    ? MaskUtil.name(member.getRealName()) : "会员");
            brief.put("phone", member.getPhone() == null ? "" : MaskUtil.phone(member.getPhone()));
            m.put("member", brief);
        }
        return m;
    }

    private String couponName(Long tplId) {
        if (tplId == null || tplId <= 0) {
            return null;
        }
        CouponTpl tpl = couponTplMapper.selectById(tplId);
        return tpl == null ? "优惠券（模板已删除）" : tpl.getTplName();
    }

    private static String businessNo(Long memberId, Integer year) {
        return "BD" + year + "-" + memberId;
    }

    private static LocalDate safeBirthday(LocalDate birthday, int year) {
        try {
            return birthday.withYear(year);
        } catch (Exception e) {
            // 2 月 29 日出生 + 平年
            return LocalDate.of(year, 2, 28);
        }
    }

    private static String msg(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String clip(String v, int max) {
        if (v == null) {
            return null;
        }
        return v.length() <= max ? v : v.substring(0, max);
    }

    private static long parseLong(String v, long def) {
        try {
            return Long.parseLong(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private Map<String, Object> item(String key, String name, Object value, String type,
                                     String desc, List<String> options) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("name", name);
        m.put("value", value);
        m.put("type", type);
        m.put("desc", desc);
        m.put("options", options);
        return m;
    }

    private void checkInt(Map<String, String> v, String key, int min, int max) {
        if (!v.containsKey(key)) {
            return;
        }
        try {
            int i = Integer.parseInt(v.get(key));
            if (i < min || i > max) {
                throw new BizException(ErrorCode.PARAM_INVALID, key + " 需在 " + min + "~" + max + " 之间");
            }
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, key + " 必须是整数");
        }
    }

    private void checkLong(Map<String, String> v, String key, long min) {
        if (!v.containsKey(key)) {
            return;
        }
        try {
            if (Long.parseLong(v.get(key)) < min) {
                throw new BizException(ErrorCode.PARAM_INVALID, key + " 不能小于 " + min);
            }
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, key + " 必须是整数");
        }
    }

    private void checkDecimal(Map<String, String> v, String key) {
        if (!v.containsKey(key)) {
            return;
        }
        try {
            if (new BigDecimal(v.get(key)).signum() < 0) {
                throw new BizException(ErrorCode.PARAM_INVALID, key + " 不能为负数");
            }
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, key + " 必须是数字");
        }
    }
}
