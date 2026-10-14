package com.company.member.common.aspect;

import com.company.member.common.annotation.Idempotent;
import com.company.member.common.annotation.NoAudit;
import com.company.member.common.annotation.OpAudit;
import com.company.member.common.constant.OperatorContext;
import com.company.member.modules.system.entity.OpLog;
import com.company.member.modules.system.mapper.OpLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 操作留痕切面 —— 把所有「会产生业务后果的写操作」落到 `m_op_log`
 *
 * <p><b>覆盖策略：opt-out（默认全记）</b>
 *
 * <p>切点匹配<b>所有控制器上的 {@code @PostMapping / @PutMapping / @DeleteMapping}</b>，
 * 只有显式标注 {@link NoAudit} 的接口才跳过。这个选择来自实测：
 * 全仓 78 个写接口里只有 8 个带 {@code @Idempotent}，
 * 如果按「加注解才记录」的思路，退单审批、次卡退卡、会员调级、RBAC 变更
 * 全都不留痕，而且以后新增的接口会继续默认不留痕 —— 这是结构性的漏，不是疏忽。
 *
 * <p>改为默认全记之后，「新增了一个写接口却忘了审计」在结构上不再可能。
 * 代价是日志表会长得快，用两个手段控制：豁免注解（需写理由）+
 * 脚本 `scripts/tools/audit-coverage.js` 复核豁免清单。
 *
 * <p><b>三个刻意的设计决定</b>：
 * <ol>
 *   <li><b>同步写、不异步</b>：异步化要么丢日志（线程池打满）、要么在业务失败时
 *       来不及记「谁尝试过什么」。一次 INSERT 约 1ms，换确定性与完整性，值得。</li>
 *   <li><b>失败也写</b>：业务抛异常时记 {@code status=0 + errorMsg} 后再原样抛出。
 *       「谁尝试改余额但失败了」和「谁改成功了」同样重要。</li>
 *   <li><b>绝不因为写日志而影响业务</b>：落库包在 try/catch 里，失败只 WARN。
 *       审计表写不进去，不该让收银结不了账。</li>
 * </ol>
 *
 * <p><b>执行顺序</b>：切面优先级 200 &gt; 幂等切面的 100，因此更靠内。
 * 被幂等拦下的重复请求不会产生审计记录 —— 否则运营连点几下就把日志表刷满，
 * 真正的操作反而被淹没。
 */
@Slf4j
@Aspect
@Component
@Order(200)
@RequiredArgsConstructor
public class OpAuditAspect {

    /** 请求参数落库前的长度上限（对应 m_op_log.request_param varchar(2000)） */
    private static final int PARAM_MAX_LEN = 1900;
    /** 错误信息落库前的长度上限（对应 error_msg varchar(1000)） */
    private static final int ERROR_MAX_LEN = 950;

    /** 参数脱敏：键名命中任一关键字即整体打码 */
    private static final String[] SECRET_KEYWORDS = {
            "password", "oldpassword", "newpassword", "secret", "privatekey",
            "apiv3key", "token", "refreshtoken", "secretkey", "signature"
    };

    /** 从方法名推断操作类型（@OpAudit 未指定时使用） */
    private static final Map<String, String> OP_TYPE_BY_METHOD = buildOpTypeMap();

    private final OpLogMapper opLogMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 切点：所有控制器的写映射（opt-out 模式）
     *
     * <p>用 {@code execution(...Controller.*(..))} 限定在控制器层，
     * 再要求方法上带三种写映射注解之一。
     * 这样 Service 之间的内部调用不会被误记 —— 只有「来自外部的一次操作」才是一条审计。
     *
     * <p><b>为什么切点不绑定 {@code @OpAudit}，而是执行时反射读取</b>：
     * 最初写成 {@code @annotation(opAudit) || @annotation(idempotent)} 并声明两个参数，
     * 启动直接失败：
     * <pre>
     *   java.lang.IllegalArgumentException: error at ::0 inconsistent binding
     * </pre>
     * AspectJ 不允许「在某一个 {@code ||} 分支上绑定、另一个分支不绑定」的形参。
     * 改为切点只做匹配判断、通知内部用
     * {@code sig.getMethod().getAnnotation(OpAudit.class)} 取值，语义完全一样且更简单。
     *
     * <p>覆盖面没有损失：全仓 {@code @Idempotent} 与 {@code @OpAudit} 都只标在
     * POST/PUT/DELETE 映射上，三种写映射已经把它们全部包含。
     */
    @Around("execution(public * com.company.member..*Controller.*(..)) && ("
            + "@annotation(org.springframework.web.bind.annotation.PostMapping) || "
            + "@annotation(org.springframework.web.bind.annotation.PutMapping) || "
            + "@annotation(org.springframework.web.bind.annotation.DeleteMapping))")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        OpAudit opAudit = sig.getMethod().getAnnotation(OpAudit.class);
        // 方法级豁免优先；没有则看类级豁免（整个控制器豁免，如小程序端自助接口）
        NoAudit noAudit = sig.getMethod().getAnnotation(NoAudit.class);
        if (noAudit == null) {
            // 用 getMethod().getDeclaringClass() 而不是 getDeclaringType()：
            // 后者返回 AspectJ 的裸 Class，getAnnotation 拿不到泛型，需要强转
            noAudit = sig.getMethod().getDeclaringClass().getAnnotation(NoAudit.class);
        }
        if (noAudit != null) {
            // 显式豁免：直接放行，不产生任何记录
            return pjp.proceed();
        }

        long start = System.currentTimeMillis();
        Object result = null;
        Throwable error = null;
        try {
            result = pjp.proceed();
            return result;
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            try {
                write(pjp, sig, opAudit, result, error, (int) (System.currentTimeMillis() - start));
            } catch (Exception e) {
                // 审计失败绝不能影响业务：收银结不了账比少一条日志严重得多
                log.warn("[审计] 操作日志落库失败（业务不受影响）：{}", e.getMessage());
            }
        }
    }

    private void write(ProceedingJoinPoint pjp, MethodSignature sig, OpAudit opAudit,
                       Object result, Throwable error, int costMs) {
        HttpServletRequest request = currentRequest();
        String uri = request == null ? null : request.getRequestURI();

        OpLog row = new OpLog();
        row.setTraceId(MDC.get("traceId"));
        row.setUserId(OperatorContext.userId());
        row.setUsername(OperatorContext.username());
        row.setStoreId(OperatorContext.storeId());

        row.setModule(resolveModule(opAudit, uri));
        row.setOpType(resolveOpType(opAudit, sig.getMethod().getName()));
        row.setOpDesc(resolveDesc(opAudit, sig));
        row.setRequestUri(uri);
        row.setRequestMethod(request == null ? null : request.getMethod());
        row.setIp(clientIp(request));
        row.setUserAgent(request == null ? null : truncate(request.getHeader("User-Agent"), 500));
        row.setCostMs(costMs);
        row.setStatus(error == null ? OpLog.ST_OK : OpLog.ST_FAIL);
        row.setErrorMsg(error == null ? null : truncate(error.getMessage(), ERROR_MAX_LEN));

        fillBizFields(row, pjp.getArgs(), result);

        boolean recordParam = opAudit == null || opAudit.recordParam();
        if (recordParam) {
            row.setRequestParam(truncate(toMaskedJson(pjp.getArgs()), PARAM_MAX_LEN));
        }

        opLogMapper.insert(row);
        log.debug("[审计] {} {} module={} opType={} status={}",
                row.getRequestMethod(), uri, row.getModule(), row.getOpType(), row.getStatus());
    }

    /* ==================== 字段推导 ==================== */

    /**
     * 模块名：优先取注解；否则从 URI 推导
     *
     * <p>URI 推导取 `/api/admin/` 之后的第一段（如 `/api/admin/accounts/adjust` → `accounts`）。
     * 保留英文复数形式而不是强行翻译 —— 它同时是接口前缀，运营和技术对得上号。
     */
    private String resolveModule(OpAudit opAudit, String uri) {
        if (opAudit != null && !opAudit.module().isBlank()) {
            return opAudit.module();
        }
        if (uri == null) {
            return "unknown";
        }
        int idx = uri.indexOf("/api/admin/");
        String rest = idx >= 0 ? uri.substring(idx + "/api/admin/".length()) : uri;
        int slash = rest.indexOf('/');
        String seg = slash > 0 ? rest.substring(0, slash) : rest;
        return seg.isBlank() ? "unknown" : truncate(seg, 50);
    }

    private String resolveOpType(OpAudit opAudit, String methodName) {
        if (opAudit != null && !opAudit.opType().isBlank()) {
            return opAudit.opType();
        }
        String byMethod = OP_TYPE_BY_METHOD.get(methodName.toLowerCase(Locale.ROOT));
        if (byMethod != null) {
            return byMethod;
        }
        // 兜底：按方法名前缀判断（saveXxx/createXxx → CREATE，updateXxx → UPDATE，…）
        String lower = methodName.toLowerCase(Locale.ROOT);
        if (lower.startsWith("create") || lower.startsWith("add") || lower.startsWith("save")
                || lower.startsWith("insert") || lower.startsWith("bind") || lower.startsWith("grant")) {
            return "CREATE";
        }
        if (lower.startsWith("update") || lower.startsWith("edit") || lower.startsWith("bind")
                || lower.startsWith("change") || lower.startsWith("set") || lower.startsWith("save")) {
            return "UPDATE";
        }
        if (lower.startsWith("remove") || lower.startsWith("delete") || lower.startsWith("void")) {
            return "DELETE";
        }
        if (lower.startsWith("export") || lower.startsWith("download")) {
            return "EXPORT";
        }
        if (lower.startsWith("import") || lower.startsWith("upload")) {
            return "IMPORT";
        }
        return "WRITE";
    }

    private String resolveDesc(OpAudit opAudit, MethodSignature sig) {
        if (opAudit != null && !opAudit.desc().isBlank()) {
            return truncate(opAudit.desc(), 255);
        }
        // 未标注时用「控制器.方法」，虽然不够友好，但至少能定位到代码
        String controller = sig.getDeclaringType().getSimpleName().replace("Controller", "");
        return truncate(controller + "." + sig.getMethod().getName(), 255);
    }

    /**
     * 从入参与返回值里挑出业务字段
     *
     * <p>用反射按固定字段名取值（{@code memberId / bizNo / reason / amount}），
     * 而不是把它做成通用引擎 —— 这三个字段是排查时真正会用到的，
     * 通用化只会让代码难以预测。
     */
    private void fillBizFields(OpLog row, Object[] args, Object result) {
        for (Object arg : args) {
            if (arg == null) {
                continue;
            }
            Object bizNo = read(arg, "bizNo", "orderNo", "paymentNo", "refundNo", "holdNo", "couponNo", "cardNo");
            if (bizNo != null && row.getBizNo() == null) {
                row.setBizNo(truncate(String.valueOf(bizNo), 32));
            }
            Object bizId = read(arg, "memberId", "id", "staffId", "userId", "tplId");
            if (bizId instanceof Number n && row.getBizId() == null) {
                row.setBizId(n.longValue());
            }
            if (row.getReason() == null) {
                Object reason = read(arg, "reason", "remark", "desc");
                if (reason != null) {
                    row.setReason(truncate(String.valueOf(reason), 500));
                }
            }
            if (row.getAmount() == null) {
                Object amount = read(arg, "amount", "balanceDelta", "rechargeAmount", "paidAmount", "faceValue");
                if (amount instanceof BigDecimal b) {
                    row.setAmount(b);
                } else if (amount instanceof Number n) {
                    row.setAmount(BigDecimal.valueOf(n.doubleValue()));
                }
            }
        }
        // 返回值里的单号/主键优先（比入参更准：新建类操作的 id 是服务端生成的）
        if (result != null) {
            // 控制器统一返回 R{code,msg,data}，业务对象在 data 里，必须先拆一层信封。
            // 不拆的话「新建会员」这类操作的 biz_id 永远是空 ——
            // 而「这笔操作到底改了哪条数据」正是审计最该回答的问题。
            Object payload = read(result, "data");
            if (payload == null) {
                payload = result;
            }
            Object bizNo = read(payload, "bizNo", "orderNo", "paymentNo", "refundNo", "holdNo", "cardNo");
            if (bizNo != null) {
                row.setBizNo(truncate(String.valueOf(bizNo), 32));
            }
            Object newId = read(payload, "id", "memberId", "staffId", "userId");
            if (newId instanceof Number n) {
                row.setBizId(n.longValue());
            }
        }
    }

    /* ==================== 参数脱敏 ==================== */

    /**
     * 参数序列化并脱敏
     *
     * <p>两步：先把对象转成 Map 树（跳过 Servlet 对象等不可序列化入参），
     * 再递归把敏感键整体替换为 {@code ***}，最后手机号做部分打码。
     *
     * <p>注意：序列化的是**对象图**而不是对象的 toString —— 后者会漏字段，
     * 而且 Lombok 生成的 toString 会把所有字段都打出来，包括不该留档的。
     */
    String toMaskedJson(Object[] args) {
        Object payload = args == null ? null
                : (args.length == 1 ? args[0] : Arrays.asList(args));
        try {
            Object tree = objectMapper.convertValue(payload, Object.class);
            Object masked = mask(tree);
            return objectMapper.writeValueAsString(masked);
        } catch (Exception e) {
            // 有不可序列化的入参（如 MultipartFile、HttpServletRequest）时退化为类型清单
            return fallbackSummary(args);
        }
    }

    private Object mask(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (isSecretKey(key)) {
                    out.put(key, "***");
                } else if ("phone".equalsIgnoreCase(key)) {
                    out.put(key, maskPhone(String.valueOf(e.getValue())));
                } else {
                    out.put(key, mask(e.getValue()));
                }
            }
            return out;
        }
        if (node instanceof Iterable<?> it) {
            java.util.List<Object> out = new java.util.ArrayList<>();
            for (Object item : it) {
                out.add(mask(item));
            }
            return out;
        }
        return node;
    }

    private static boolean isSecretKey(String key) {
        String k = key.toLowerCase(Locale.ROOT).replace("_", "");
        for (String s : SECRET_KEYWORDS) {
            if (k.contains(s)) {
                return true;
            }
        }
        return false;
    }

    /** 手机号打码：138****8000（保留前 3 后 4，便于核对归属又不泄露完整号码） */
    static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private String fallbackSummary(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(args[i] == null ? "null" : args[i].getClass().getSimpleName());
        }
        return sb.append("]（含不可序列化入参，仅记录类型）").toString();
    }

    /* ==================== 工具 ==================== */

    private static Object read(Object target, String... fieldNames) {
        if (target == null) {
            return null;
        }
        Class<?> clazz = target.getClass();
        for (String name : fieldNames) {
            Class<?> c = clazz;
            while (c != null && c != Object.class) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    Object v = f.get(target);
                    if (v != null) {
                        return v;
                    }
                    break;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                } catch (IllegalAccessException e) {
                    break;
                }
            }
            // 也支持 record / Map 形态
            if (target instanceof Map<?, ?> m && m.containsKey(name)) {
                return m.get(name);
            }
        }
        return null;
    }

    private static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        for (String header : new String[]{"X-Forwarded-For", "X-Real-IP"}) {
            String v = request.getHeader(header);
            if (v != null && !v.isBlank() && !"unknown".equalsIgnoreCase(v)) {
                int comma = v.indexOf(',');
                return truncate(comma > 0 ? v.substring(0, comma).trim() : v.trim(), 64);
            }
        }
        return truncate(request.getRemoteAddr(), 64);
    }

    private static String truncate(String v, int max) {
        if (v == null) {
            return null;
        }
        return v.length() <= max ? v : v.substring(0, max);
    }

    private HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest();
        }
        return null;
    }

    /** 方法名 → 操作类型（只列真正需要区分的业务动作，其余走上文的前缀兜底） */
    private static Map<String, String> buildOpTypeMap() {
        Map<String, String> m = new java.util.HashMap<>();
        m.put("settle", "CONSUME");
        m.put("preview", "CONSUME");
        m.put("recharge", "RECHARGE");
        m.put("refund", "REFUND");
        m.put("approve", "AUDIT");
        m.put("reject", "AUDIT");
        m.put("adjust", "ADJUST");
        m.put("reconcile", "AUDIT");
        m.put("verify", "VERIFY");
        m.put("use", "VERIFY");
        m.put("voidcoupon", "DELETE");
        m.put("grant", "GRANT");
        m.put("grantbatch", "GRANT");
        m.put("hold", "CONSUME");
        m.put("resume", "CONSUME");
        m.put("sell", "SALE");
        m.put("extend", "UPDATE");
        m.put("freeze", "UPDATE");
        m.put("recalculate", "UPDATE");
        m.put("evaluate", "UPDATE");
        m.put("leave", "UPDATE");
        m.put("reinstate", "UPDATE");
        m.put("invalidate", "UPDATE");
        m.put("resend", "RETRY");
        m.put("issubatch", "GRANT");
        m.put("resetpassword", "UPDATE");
        m.put("changestatus", "UPDATE");
        m.put("changepassword", "UPDATE");
        m.put("savemenus", "UPDATE");
        m.put("saverule", "UPDATE");
        m.put("saveconfig", "UPDATE");
        return m;
    }
}
