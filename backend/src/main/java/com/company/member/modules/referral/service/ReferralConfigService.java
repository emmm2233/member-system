package com.company.member.modules.referral.service;

import com.company.member.common.exception.BizException;
import com.company.member.common.result.ErrorCode;
import com.company.member.modules.system.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 推荐有礼规则配置（全部落在 m_sys_config 的 referral.* 键上）
 *
 * <p>业务阈值一律从配置读，禁止硬编码——这是本项目的铁律之一。
 * 本服务额外承担「配置项自描述 + 写入校验」两件事，让管理端不需要硬编码表单。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReferralConfigService {

    /** 允许的奖励类型 */
    public static final Set<String> REWARD_TYPES = new LinkedHashSet<>(
            List.of("NONE", "POINTS", "BALANCE", "GIFT_BALANCE", "COUPON", "GROWTH"));

    /** 允许的结算触发时机 */
    public static final Set<String> SETTLE_MODES = new LinkedHashSet<>(
            List.of("REGISTER", "FIRST_CONSUME", "FIRST_RECHARGE"));

    private final ConfigService configService;

    /**
     * 邀请码签名密钥
     *
     * <p>生产环境必须在 m_sys_config 配置 {@code referral.invite-secret}；
     * 缺省值只用于开发期，改这个值会让<b>所有旧邀请码失效</b>（这是可接受的运维语义，
     * 但不要在活动进行中改）。
     */
    public String secret() {
        return configService.getString("referral.invite-secret", "member-system-referral-v1");
    }

    /**
     * 推荐规则快照
     *
     * @param enabled             总开关
     * @param settleMode          结算时机：REGISTER 注册即结算 / FIRST_CONSUME 首单 / FIRST_RECHARGE 首次充值
     * @param bindWindowDays      绑定窗口（天）：被推荐人注册后多少天内可绑定，0 表示不限
     * @param minAmount           触发结算的最低实付金额
     * @param requirePhone        被推荐人必须已绑手机（防批量虚假注册）
     * @param maxPerReferrerMonth 推荐人每月结算上限，0 表示不限
     * @param referrerType        给推荐人的奖励类型
     * @param referrerValue       给推荐人的奖励值
     * @param referrerCouponTplId 给推荐人的券模板 id（类型为 COUPON 时必填）
     * @param inviteeType         给被推荐人的奖励类型
     * @param inviteeValue        给被推荐人的奖励值
     * @param inviteeCouponTplId  给被推荐人的券模板 id
     * @param ruleDesc            活动规则文案（小程序展示）
     * @param posterUrl           推广海报/小程序码图片地址
     */
    public record Cfg(boolean enabled, String settleMode, int bindWindowDays, BigDecimal minAmount,
                      boolean requirePhone, int maxPerReferrerMonth,
                      String referrerType, BigDecimal referrerValue, Long referrerCouponTplId,
                      String inviteeType, BigDecimal inviteeValue, Long inviteeCouponTplId,
                      String ruleDesc, String posterUrl) {
    }

    public Cfg load() {
        return new Cfg(
                configService.getBoolean("referral.enabled", false),
                configService.getString("referral.settle-mode", "FIRST_CONSUME").trim().toUpperCase(),
                configService.getInt("referral.bind-window-days", 7),
                configService.getDecimal("referral.min-amount", BigDecimal.ZERO),
                configService.getBoolean("referral.require-phone", true),
                configService.getInt("referral.max-per-referrer-month", 0),
                configService.getString("referral.reward-referrer-type", "POINTS").trim().toUpperCase(),
                configService.getDecimal("referral.reward-referrer-value", BigDecimal.ZERO),
                configService.getLong("referral.reward-referrer-coupon-tpl-id", 0L),
                configService.getString("referral.reward-invitee-type", "COUPON").trim().toUpperCase(),
                configService.getDecimal("referral.reward-invitee-value", BigDecimal.ZERO),
                configService.getLong("referral.reward-invitee-coupon-tpl-id", 0L),
                configService.getString("referral.rule-desc", ""),
                configService.getString("referral.poster-url", ""));
    }

    /** 管理端表单描述（含当前值、取值范围与说明） */
    public List<Map<String, Object>> describe() {
        Cfg c = load();
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(item("referral.enabled", "推荐有礼开关", c.enabled(), "BOOLEAN",
                "关闭后不新增推荐关系，已绑定的也不再结算", null));
        out.add(item("referral.settle-mode", "结算时机", c.settleMode(), "ENUM",
                "REGISTER 注册即奖 / FIRST_CONSUME 被推荐人首单 / FIRST_RECHARGE 被推荐人首次充值",
                new ArrayList<>(SETTLE_MODES)));
        out.add(item("referral.bind-window-days", "绑定窗口(天)", c.bindWindowDays(), "NUMBER",
                "被推荐人注册后多少天内可绑定推荐人，0 表示不限", null));
        out.add(item("referral.min-amount", "触发门槛(元)", c.minAmount(), "DECIMAL",
                "被推荐人首单实付金额需达到该值才触发奖励", null));
        out.add(item("referral.require-phone", "要求绑定手机号", c.requirePhone(), "BOOLEAN",
                "开启后未绑手机的被推荐人不能绑定推荐人（防批量虚假注册）", null));
        out.add(item("referral.max-per-referrer-month", "推荐人每月上限(单)", c.maxPerReferrerMonth(), "NUMBER",
                "0 表示不限；超出后推荐人不再获得奖励，被推荐人奖励照发", null));
        out.add(item("referral.reward-referrer-type", "推荐人奖励类型", c.referrerType(), "ENUM",
                "NONE 不奖励", new ArrayList<>(REWARD_TYPES)));
        out.add(item("referral.reward-referrer-value", "推荐人奖励值", c.referrerValue(), "DECIMAL",
                "积分/成长值取整数部分；余额类按元", null));
        out.add(item("referral.reward-referrer-coupon-tpl-id", "推荐人券模板ID", c.referrerCouponTplId(), "NUMBER",
                "奖励类型为 COUPON 时必填", null));
        out.add(item("referral.reward-invitee-type", "被推荐人奖励类型", c.inviteeType(), "ENUM",
                "NONE 不奖励", new ArrayList<>(REWARD_TYPES)));
        out.add(item("referral.reward-invitee-value", "被推荐人奖励值", c.inviteeValue(), "DECIMAL",
                "积分/成长值取整数部分；余额类按元", null));
        out.add(item("referral.reward-invitee-coupon-tpl-id", "被推荐人券模板ID", c.inviteeCouponTplId(), "NUMBER",
                "奖励类型为 COUPON 时必填", null));
        out.add(item("referral.rule-desc", "活动规则文案", c.ruleDesc(), "TEXT",
                "展示在小程序推荐有礼页面", null));
        out.add(item("referral.poster-url", "推广海报地址", c.posterUrl(), "TEXT",
                "小程序分享海报图 URL，留空则前端用默认样式生成", null));
        return out;
    }

    /** 写入前校验，避免把非法规则落库导致线上结算异常 */
    public void update(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        Map<String, String> cleaned = new LinkedHashMap<>();
        values.forEach((k, v) -> {
            if (k == null || v == null) {
                return;
            }
            cleaned.put(k, v.trim());
        });

        checkEnum(cleaned, "referral.settle-mode", SETTLE_MODES);
        checkEnum(cleaned, "referral.reward-referrer-type", REWARD_TYPES);
        checkEnum(cleaned, "referral.reward-invitee-type", REWARD_TYPES);
        checkDecimal(cleaned, "referral.min-amount");
        checkDecimal(cleaned, "referral.reward-referrer-value");
        checkDecimal(cleaned, "referral.reward-invitee-value");
        checkInt(cleaned, "referral.bind-window-days", 0, 3650);
        checkInt(cleaned, "referral.max-per-referrer-month", 0, 100000);

        // 券奖励必须给出券模板 id，否则结算时才失败会污染奖励记录
        requireCouponTpl(cleaned, "referral.reward-referrer-type", "referral.reward-referrer-coupon-tpl-id");
        requireCouponTpl(cleaned, "referral.reward-invitee-type", "referral.reward-invitee-coupon-tpl-id");

        configService.updateBatch(cleaned);
        log.info("[推荐] 规则已更新，共 {} 项", cleaned.size());
    }

    private void requireCouponTpl(Map<String, String> values, String typeKey, String tplKey) {
        String type = values.get(typeKey);
        if (type == null) {
            type = configService.getString(typeKey, "");
        }
        if (!"COUPON".equalsIgnoreCase(type)) {
            return;
        }
        String tplId = values.get(tplKey);
        if (tplId == null) {
            tplId = configService.getString(tplKey, "0");
        }
        try {
            if (Long.parseLong(tplId.trim()) <= 0) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "奖励类型为 COUPON 时必须指定有效的券模板 ID（" + tplKey + "）");
            }
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, tplKey + " 必须是数字");
        }
    }

    private void checkEnum(Map<String, String> values, String key, Set<String> allowed) {
        String v = values.get(key);
        if (v == null) {
            return;
        }
        if (!allowed.contains(v.toUpperCase())) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    key + " 取值非法：" + v + "，允许 " + String.join("/", allowed));
        }
        values.put(key, v.toUpperCase());
    }

    private void checkDecimal(Map<String, String> values, String key) {
        String v = values.get(key);
        if (v == null) {
            return;
        }
        try {
            BigDecimal d = new BigDecimal(v);
            if (d.signum() < 0) {
                throw new BizException(ErrorCode.PARAM_INVALID, key + " 不能为负数");
            }
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, key + " 必须是数字");
        }
    }

    private void checkInt(Map<String, String> values, String key, int min, int max) {
        String v = values.get(key);
        if (v == null) {
            return;
        }
        try {
            int i = Integer.parseInt(v);
            if (i < min || i > max) {
                throw new BizException(ErrorCode.PARAM_INVALID, key + " 需在 " + min + "~" + max + " 之间");
            }
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, key + " 必须是整数");
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
}
