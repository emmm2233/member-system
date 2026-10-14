package com.company.member.modules.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.member.common.cache.CacheService;
import com.company.member.common.exception.BizException;
import com.company.member.common.result.ErrorCode;
import com.company.member.modules.system.entity.SysConfig;
import com.company.member.modules.system.mapper.SysConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 系统参数服务（带缓存）
 *
 * <p>铁律：业务阈值一律从这里读，禁止硬编码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigService {

    private static final String CACHE_KEY_ALL = "sysconfig:all";
    private static final String CACHE_KEY_ONE = "sysconfig:one:";

    private final SysConfigMapper configMapper;
    private final CacheService cacheService;

    /** 全量参数（Map，短时缓存） */
    public Map<String, String> all() {
        String cached = cacheService.getString(CACHE_KEY_ALL);
        if (cached != null && !cached.isBlank()) {
            return parse(cached);
        }
        List<SysConfig> list = configMapper.selectList(new LambdaQueryWrapper<>());
        Map<String, String> map = list.stream()
                .collect(Collectors.toMap(SysConfig::getConfigKey, c -> c.getConfigValue() == null ? "" : c.getConfigValue(),
                        (a, b) -> b));
        cacheService.set(CACHE_KEY_ALL, serialize(map), 10, TimeUnit.MINUTES);
        return map;
    }

    public String getString(String key, String defaultValue) {
        String v = all().get(key);
        return v == null || v.isBlank() ? defaultValue : v;
    }

    public int getInt(String key, int defaultValue) {
        String v = getString(key, null);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            log.warn("[配置] {} 值非法：{}，使用默认值 {}", key, v, defaultValue);
            return defaultValue;
        }
    }

    public long getLong(String key, long defaultValue) {
        String v = getString(key, null);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public BigDecimal getDecimal(String key, BigDecimal defaultValue) {
        String v = getString(key, null);
        if (v == null) {
            return defaultValue;
        }
        try {
            return new BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String v = getString(key, null);
        if (v == null) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim());
    }

    /* ==================== 业务语义化读取 ==================== */

    /** 余额扣减优先级：true=先扣赠送 */
    public boolean giftFirst() {
        return "GIFT_FIRST".equalsIgnoreCase(getString("account.deduct_priority", "GIFT_FIRST"));
    }

    public BigDecimal maxBalance() {
        return getDecimal("account.max_balance", BigDecimal.ZERO);
    }

    public BigDecimal giftRatioLimit() {
        return getDecimal("account.gift_ratio_limit", BigDecimal.ONE);
    }

    /** 多少积分抵 1 元 */
    public int pointsExchangeRate() {
        return getInt("points.exchange_rate", 100);
    }

    /** 单笔积分最高抵扣比例 */
    public BigDecimal pointsMaxDeductRatio() {
        return getDecimal("points.max_deduct_ratio", new BigDecimal("0.30"));
    }

    /** 积分有效期（月），0 表示永久 */
    public int pointsExpireMonths() {
        return getInt("points.expire_months", 12);
    }

    public long pointsMinKeep() {
        return getLong("points.min_keep", 0L);
    }

    /** 每 1 元实付得积分 */
    public BigDecimal pointsPerYuan() {
        return getDecimal("points.per_yuan", BigDecimal.ONE);
    }

    /** 每 1 元实付得成长值 */
    public BigDecimal growthPerYuan() {
        return getDecimal("growth.per_yuan", BigDecimal.ONE);
    }

    public boolean downgradeEnabled() {
        return getBoolean("level.downgrade_enabled", false);
    }

    public int levelValidMonths() {
        return getInt("level.valid_months", 0);
    }

    public boolean autoApproveRefund() {
        return getBoolean("order.auto_approve_refund", false);
    }

    public BigDecimal largeRechargeThreshold() {
        return getDecimal("order.large_recharge_need_approve", new BigDecimal("5000"));
    }

    /** 券到期提醒天数列表 */
    public List<Integer> couponNotifyDays() {
        String v = getString("coupon.notify_days_before_expire", "7,3,1");
        return java.util.Arrays.stream(v.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(Integer::valueOf).toList();
    }

    public boolean wxSubscribeEnabled() {
        return getBoolean("wx.subscribe.enabled", true);
    }

    public int dashboardCacheSeconds() {
        return getInt("report.dashboard_cache_seconds", 600);
    }

    /* ==================== 支付相关（预留） ==================== */

    public boolean payEnabled() {
        return getBoolean("pay.enabled", false);
    }

    public boolean payMockEnabled() {
        return getBoolean("pay.mock-enabled", true);
    }

    public String payDefaultChannel() {
        return getString("pay.default-channel", "WECHAT");
    }

    public List<String> payChannels() {
        String v = getString("pay.channels", "MOCK,WECHAT");
        return java.util.Arrays.stream(v.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).map(String::toUpperCase).toList();
    }

    public int payTimeoutMinutes() {
        return getInt("pay.timeout-minutes", 15);
    }

    public String payNotifyUrl() {
        return getString("pay.notify-url", "");
    }

    public int payAutoSyncSeconds() {
        return getInt("pay.auto-sync-seconds", 30);
    }

    public int payMaxQueryTimes() {
        return getInt("pay.max-query-times", 5);
    }

    /* ==================== 管理 ==================== */

    public List<SysConfig> listAll() {
        return configMapper.selectList(new LambdaQueryWrapper<SysConfig>().orderByAsc(SysConfig::getConfigGroup)
                .orderByAsc(SysConfig::getId));
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(String key, String value) {
        SysConfig config = configMapper.selectOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, key));
        if (config == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "参数不存在：" + key);
        }
        if (config.getEditable() != null && config.getEditable() == 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED, "该参数不允许修改：" + key);
        }
        config.setConfigValue(value);
        configMapper.updateById(config);
        clearCache();
        log.info("[配置] 更新 {} = {}", key, value);
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateBatch(Map<String, String> values) {
        values.forEach(this::update);
    }

    public void clearCache() {
        cacheService.delete(CACHE_KEY_ALL);
    }

    private Map<String, String> parse(String serialized) {
        Map<String, String> map = new java.util.HashMap<>();
        if (serialized.isBlank()) {
            return map;
        }
        for (String pair : serialized.split("\u0001")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                map.put(pair.substring(0, idx), pair.substring(idx + 1));
            }
        }
        return map;
    }

    private String serialize(Map<String, String> map) {
        return map.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("\u0001"));
    }
}
