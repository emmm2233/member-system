package com.company.member.common.cache;

import com.company.member.modules.system.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 缓存与多实例就绪检测
 *
 * <p><b>要解决的问题</b>：Caffeine 与 Redis 两套实现在「存个查询结果」这类场景下可以互换，
 * 但本项目缓存里放的<b>不全是性能数据</b>，还有正确性数据：
 *
 * <table border="1">
 *   <caption>多实例 + Caffeine 的失效后果</caption>
 *   <tr><th>缓存内容</th><th>失效后果</th></tr>
 *   <tr><td>{@code idem:*} 幂等键</td><td>幂等只在本实例生效 → <b>跨实例重复提交挡不住</b></td></tr>
 *   <tr><td>{@code login:fail:*} 登录失败计数</td><td>锁定只在本实例生效 → <b>撞库绕过锁定</b></td></tr>
 *   <tr><td>{@code seq:*} 业务序列号</td><td>两个实例可能算出同一单号（有重试兜底但会退化）</td></tr>
 *   <tr><td>{@code loginuser:*} 登录态</td><td>改权限后另一实例仍是旧权限（最长 5 分钟窗口）</td></tr>
 *   <tr><td>{@code wxpay:*} 平台证书/token</td><td>各实例各取一次，微信侧会互相顶掉 access_token</td></tr>
 * </table>
 *
 * <p>这些失效<b>不会报任何错</b>，只会静默地不安全。所以除了「切 Redis」这一个动作，
 * 还需要一个能主动喊出来的守卫 —— 这就是本类。
 *
 * <p>触发方式：
 * <ul>
 *   <li>启动完成时（{@link ApplicationReadyEvent}）检查一次并打 WARN</li>
 *   <li>{@code /actuator/health} 之外，业务侧可通过 {@link #status()} 主动查询（管理端展示）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheReadinessChecker {

    private final ConfigService configService;
    private final CacheService cacheService;

    /** 实际生效的缓存实现类型（由 CacheConfig 注入；默认值与 application.yml 保持一致） */
    @Value("${member.cache.type:local}")
    private String cacheType;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Map<String, Object> st = status();
        boolean safe = Boolean.TRUE.equals(st.get("multiInstanceSafe"));
        if (safe) {
            log.info("[缓存] 实现={}, 多实例={} —— 幂等键/登录锁定/序列号跨实例一致",
                    st.get("cacheType"), st.get("multiInstance"));
            return;
        }
        // 关键：不是「性能警告」，而是「正确性警告」，措辞必须让人立刻明白风险
        log.warn("""

                ============================================================
                 [缓存安全警告] 当前声明为多实例部署，但缓存实现是 {}。
                 以下能力在多实例下会【静默失效】：
                   · 幂等键（跨实例重复提交挡不住）
                   · 登录失败锁定（请求打到另一实例即绕过）
                   · 业务序列号（两个实例可能生成相同单号）
                   · 微信 access_token（实例间互相顶掉）
                 解决：application.yml 里把 member.cache.type 改为 redis 并配置 spring.data.redis.*，
                 或确认当前确实只有单实例（把 cache.multi-instance 置 false 可消除本警告）。
                 详见 docs/13-优化方案.md §P2-2
                ============================================================
                """, st.get("cacheType"));
    }

    /** 就绪状态（管理端与自检使用） */
    public Map<String, Object> status() {
        boolean redis = cacheService instanceof RedisCacheServiceImpl
                || "redis".equalsIgnoreCase(cacheType);
        boolean multi = configService.getBoolean("cache.multi-instance", false);
        List<String> risks = new ArrayList<>();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cacheType", redis ? "redis" : "caffeine");
        m.put("configuredType", cacheType);
        m.put("multiInstance", multi);
        m.put("multiInstanceSafe", redis || !multi);

        if (!redis) {
            risks.add("幂等键只在本实例生效，跨实例重复提交挡不住");
            risks.add("登录失败锁定只在本实例生效，撞库可绕过");
            risks.add("业务序列号不跨实例，两个实例可能生成相同单号（有重试兜底）");
            risks.add("微信平台证书与 access_token 各实例独立，会互相顶掉");
        }
        if (multi && !redis) {
            risks.add("【当前配置不安全】已声明多实例部署，请切 Redis");
        }
        m.put("risks", risks);
        m.put("suggestion", redis
                ? "已使用 Redis，多实例安全"
                : (multi ? "立即执行：application.yml 的 member.cache.type 改为 redis，配置 spring.data.redis.* 后重启"
                         : "单实例部署可继续用 caffeine；一旦要多实例，先切 Redis 再扩容"));
        return m;
    }
}
