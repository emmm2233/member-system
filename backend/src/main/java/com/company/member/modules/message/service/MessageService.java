package com.company.member.modules.message.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.member.common.exception.BizException;
import com.company.member.common.result.ErrorCode;
import com.company.member.common.result.PageResult;
import com.company.member.common.util.MaskUtil;
import com.company.member.modules.member.entity.Member;
import com.company.member.modules.member.mapper.MemberMapper;
import com.company.member.modules.message.channel.MessageChannel;
import com.company.member.modules.message.channel.MessageChannelRegistry;
import com.company.member.modules.message.channel.WxSubscribeChannel;
import com.company.member.modules.message.entity.MessageRecord;
import com.company.member.modules.message.entity.MessageSubscribe;
import com.company.member.modules.message.entity.MessageTpl;
import com.company.member.modules.message.mapper.MessageRecordMapper;
import com.company.member.modules.message.mapper.MessageSubscribeMapper;
import com.company.member.modules.message.mapper.MessageTplMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息服务：模板渲染 → 渠道投递 → 落库 → 失败重试
 *
 * <p><b>三条不变量</b>：
 * <ol>
 *   <li><b>幂等</b>：{@code idempotencyKey} 有唯一索引，同一业务动作（同一笔生日礼、
 *       同一笔推荐奖励）重复调用只会得到同一条记录，不会给用户发两条。</li>
 *   <li><b>不丢内容</b>：模板渠道不可用（未配 AppID/额度耗尽/用户拒收）时降级为站内信，
 *       用户仍能在小程序消息中心看到完整内容。</li>
 *   <li><b>不阻断主链路</b>：{@link #sendQuietly} 吞掉一切异常，收银/发奖事务不受消息影响；
 *       失败记录留待定时任务重试。</li>
 * </ol>
 *
 * <p><b>事务注意</b>：{@link #send} 使用 {@code REQUIRES_NEW}，避免消息表写入失败
 * 把业务事务标记为 rollback-only；同时保证「业务已提交但消息还没发」这种中间态可被重试任务捞起。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private static final int MAX_RETRY = 5;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final MessageTplMapper tplMapper;
    private final MessageRecordMapper recordMapper;
    private final MessageSubscribeMapper subscribeMapper;
    private final MessageChannelRegistry channelRegistry;
    private final MemberMapper memberMapper;

    /* ==================== 1. 发送 ==================== */

    /**
     * 发送消息（幂等）
     *
     * @param tplCode   模板编码
     * @param memberId  接收会员
     * @param vars      模板变量（可为 null）
     * @param bizNo     关联业务单号（可为 null）
     * @param idemKey   幂等键；为 null 时按 {@code tplCode:memberId:yyyyMMddHHmmss} 生成（即不幂等）
     * @return 消息记录；模板不存在或停用时返回 null
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public MessageRecord send(String tplCode, Long memberId, Map<String, Object> vars,
                              String bizNo, String idemKey) {
        if (memberId == null) {
            log.warn("[消息] 缺少接收人，跳过 tplCode={}", tplCode);
            return null;
        }
        String key = idemKey == null || idemKey.isBlank()
                ? tplCode + ":" + memberId + ":" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"))
                : idemKey;

        // 幂等前置检查：已发过直接返回（唯一索引是最终防线）
        MessageRecord existed = recordMapper.selectByIdemKey(key);
        if (existed != null) {
            log.debug("[消息] 幂等命中，跳过发送 key={}", key);
            return existed;
        }

        MessageTpl tpl = findByCode(tplCode);
        if (tpl == null) {
            log.warn("[消息] 模板不存在或已停用 tplCode={}", tplCode);
            return null;
        }

        Member member = memberMapper.selectById(memberId);
        Map<String, Object> ctx = buildContext(member, vars, tpl);
        MessageRecord record = new MessageRecord();
        record.setTplCode(tplCode);
        record.setChannel(tpl.getChannel());
        record.setMemberId(memberId);
        record.setOpenid(member == null ? null : member.getOpenid());
        record.setTitle(render(tpl.getTitle(), ctx));
        record.setContent(render(tpl.getContentTpl(), ctx));
        record.setBizNo(bizNo);
        record.setStatus(MessageRecord.ST_PENDING);
        record.setRetryCount(0);
        record.setIdempotencyKey(key);

        try {
            recordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发下另一个线程先插入了同一条，直接复用
            MessageRecord dup = recordMapper.selectByIdemKey(key);
            if (dup != null) {
                return dup;
            }
            throw e;
        }

        deliver(tpl, record);
        return record;
    }

    /**
     * 静默发送：任何异常都不外抛。用于收银、发奖等不能因通知失败而回滚的场景。
     *
     * <p>注意：{@code REQUIRES_NEW} 意味着消息的提交与业务事务无关，业务回滚时消息可能已发出。
     * 因此只应在<b>业务事务提交之后</b>（事件监听器 AFTER_COMMIT）调用。
     */
    public void sendQuietly(String tplCode, Long memberId, Map<String, Object> vars,
                            String bizNo, String idemKey) {
        try {
            send(tplCode, memberId, vars, bizNo, idemKey);
        } catch (Exception e) {
            log.warn("[消息] 静默发送失败 tplCode={}, memberId={}, 原因={}", tplCode, memberId, e.getMessage());
        }
    }

    /** 执行一次真实投递并落库结果 */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void deliver(MessageTpl tpl, MessageRecord record) {
        MessageChannel channel = channelRegistry.find(tpl.getChannel()).orElse(null);
        String degradeReason = null;

        if (channel == null) {
            degradeReason = "渠道未实现: " + tpl.getChannel();
        } else if (!channel.available()) {
            degradeReason = channel.name() + " 不可用：" + channel.unavailableReason();
        }

        MessageChannel.SendResult result;
        if (degradeReason != null) {
            result = MessageChannel.SendResult.fail(degradeReason);
        } else {
            result = channel.send(tpl, record);
            // 订阅额度耗尽不是「失败」，而是「该渠道暂时没法用」，同样降级到站内信
            if (!result.success() && WxSubscribeChannel.ERR_NO_QUOTA.equals(result.error())) {
                degradeReason = "订阅消息额度已用尽（用户未再次授权）";
                result = MessageChannel.SendResult.fail(degradeReason);
            }
        }

        if (!result.success() && degradeReason != null) {
            // 降级：换站内信渠道重发一次，内容不丢
            MessageChannel fallback = channelRegistry.fallback();
            MessageChannel.SendResult fb = fallback.send(tpl, record);
            record.setChannel(fallback.code());
            if (fb.success()) {
                record.setStatus(MessageRecord.ST_SENT);
                record.setSentAt(LocalDateTime.now());
                record.setErrorMsg("已降级站内信：" + degradeReason);
            } else {
                record.setStatus(MessageRecord.ST_FAILED);
                record.setErrorMsg(degradeReason);
                record.setRetryCount(1);
                record.setNextRetryAt(nextRetryAt(1));
            }
            recordMapper.updateById(record);
            log.info("[消息] {} 降级为站内信 memberId={}, tpl={}, 原因={}",
                    tpl.getChannel(), record.getMemberId(), tpl.getTplCode(), degradeReason);
            return;
        }

        if (result.success()) {
            record.setStatus(MessageRecord.ST_SENT);
            record.setSentAt(LocalDateTime.now());
            record.setErrorMsg(null);
        } else {
            record.setStatus(MessageRecord.ST_FAILED);
            record.setErrorMsg(clip(result.error(), 500));
            record.setRetryCount(record.getRetryCount() == null ? 1 : record.getRetryCount() + 1);
            record.setNextRetryAt(nextRetryAt(record.getRetryCount()));
        }
        recordMapper.updateById(record);
    }

    /**
     * 重试失败消息
     *
     * @return 处理条数
     */
    @Transactional(rollbackFor = Exception.class)
    public int retryPending(int limit) {
        List<MessageRecord> list = recordMapper.selectRetryable(MAX_RETRY, limit);
        int done = 0;
        for (MessageRecord record : list) {
            MessageTpl tpl = findByCode(record.getTplCode());
            if (tpl == null) {
                record.setStatus(MessageRecord.ST_FAILED);
                record.setRetryCount(MAX_RETRY);
                record.setNextRetryAt(null);
                record.setErrorMsg("模板已删除或停用，放弃重试");
                recordMapper.updateById(record);
                continue;
            }
            try {
                deliver(tpl, record);
                done++;
            } catch (Exception e) {
                log.warn("[消息] 重试异常 id={}, 原因={}", record.getId(), e.getMessage());
                record.setRetryCount((record.getRetryCount() == null ? 0 : record.getRetryCount()) + 1);
                record.setNextRetryAt(nextRetryAt(record.getRetryCount()));
                record.setErrorMsg(clip(e.getMessage(), 500));
                recordMapper.updateById(record);
            }
        }
        return done;
    }

    /** 手动重发（管理端）；重置重试计数以便立即投递 */
    @Transactional(rollbackFor = Exception.class)
    public void resend(Long recordId) {
        MessageRecord record = recordMapper.selectById(recordId);
        if (record == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "消息记录不存在");
        }
        MessageTpl tpl = findByCode(record.getTplCode());
        if (tpl == null) {
            throw new BizException(ErrorCode.OPERATION_FAILED, "模板不存在或已停用：" + record.getTplCode());
        }
        record.setRetryCount(0);
        record.setNextRetryAt(null);
        deliver(tpl, record);
        if (record.getStatus() == MessageRecord.ST_FAILED) {
            throw new BizException(ErrorCode.OPERATION_FAILED, "重发失败：" + record.getErrorMsg());
        }
    }

    /** 指数退避：2^retry 分钟，上限 60 分钟 */
    private static LocalDateTime nextRetryAt(int retryCount) {
        int minutes = Math.min(60, (int) Math.pow(2, Math.max(1, Math.min(retryCount, 6))));
        return LocalDateTime.now().plusMinutes(minutes);
    }

    /* ==================== 2. 模板 ==================== */

    public MessageTpl findByCode(String tplCode) {
        return tplMapper.selectOne(new LambdaQueryWrapper<MessageTpl>()
                .eq(MessageTpl::getTplCode, tplCode)
                .eq(MessageTpl::getStatus, 1)
                .last("LIMIT 1"));
    }

    public List<Map<String, Object>> listTpls() {
        List<MessageTpl> list = tplMapper.selectList(new LambdaQueryWrapper<MessageTpl>()
                .orderByAsc(MessageTpl::getId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (MessageTpl t : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("tplCode", t.getTplCode());
            m.put("tplName", t.getTplName());
            m.put("channel", t.getChannel());
            m.put("channelText", MessageTpl.channelText(t.getChannel()));
            m.put("wxTemplateId", t.getWxTemplateId());
            m.put("wxDataMap", t.getWxDataMap());
            m.put("title", t.getTitle());
            m.put("contentTpl", t.getContentTpl());
            m.put("pagePath", t.getPagePath());
            m.put("status", t.getStatus());
            m.put("remark", t.getRemark());
            m.put("placeholders", extractPlaceholders(t.getContentTpl()));
            out.add(m);
        }
        return out;
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateTpl(Long id, Map<String, Object> body) {
        MessageTpl tpl = tplMapper.selectById(id);
        if (tpl == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "消息模板不存在");
        }
        if (body.containsKey("tplName")) {
            tpl.setTplName(str(body.get("tplName")));
        }
        if (body.containsKey("wxTemplateId")) {
            tpl.setWxTemplateId(str(body.get("wxTemplateId")));
        }
        if (body.containsKey("wxDataMap")) {
            String map = str(body.get("wxDataMap"));
            if (map != null && !map.isBlank()) {
                validateJson(map);
            }
            tpl.setWxDataMap(map);
        }
        if (body.containsKey("title")) {
            tpl.setTitle(str(body.get("title")));
        }
        if (body.containsKey("contentTpl")) {
            String content = str(body.get("contentTpl"));
            if (content == null || content.isBlank()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "消息内容不能为空");
            }
            tpl.setContentTpl(content);
        }
        if (body.containsKey("pagePath")) {
            tpl.setPagePath(str(body.get("pagePath")));
        }
        if (body.containsKey("status")) {
            tpl.setStatus(intVal(body.get("status"), 1));
        }
        if (body.containsKey("remark")) {
            tpl.setRemark(str(body.get("remark")));
        }
        tplMapper.updateById(tpl);
        log.info("[消息] 模板已更新 id={}, code={}", id, tpl.getTplCode());
    }

    /* ==================== 3. 订阅授权额度 ==================== */

    /**
     * 小程序端上报「用户已授权的模板 ID」
     *
     * @param memberId    会员
     * @param templateIds 微信模板 ID 列表（用户点「允许」的那些）
     * @return 本轮新增额度
     */
    @Transactional(rollbackFor = Exception.class)
    public int reportSubscribe(Long memberId, List<String> templateIds) {
        if (templateIds == null || templateIds.isEmpty()) {
            return 0;
        }
        int granted = 0;
        for (String tid : templateIds) {
            if (tid == null || tid.isBlank()) {
                continue;
            }
            String code = tplMapper.selectList(new LambdaQueryWrapper<MessageTpl>()
                            .eq(MessageTpl::getWxTemplateId, tid).last("LIMIT 1"))
                    .stream().findFirst().map(MessageTpl::getTplCode).orElse(null);
            if (subscribeMapper.increaseQuota(memberId, tid, code, 1) == 0) {
                // 首次授权：建行；并发下可能撞 uk_member_tpl，回头累加即可
                MessageSubscribe row = new MessageSubscribe();
                row.setMemberId(memberId);
                row.setWxTemplateId(tid);
                row.setTplCode(code);
                row.setRemainCount(1);
                row.setTotalCount(1);
                row.setLastGrantAt(LocalDateTime.now());
                try {
                    subscribeMapper.insert(row);
                } catch (DuplicateKeyException e) {
                    subscribeMapper.increaseQuota(memberId, tid, code, 1);
                }
            }
            granted++;
        }
        log.info("[消息] 订阅授权上报 memberId={}, 模板数={}", memberId, granted);
        return granted;
    }

    public List<Map<String, Object>> mySubscribeQuota(Long memberId) {
        List<MessageSubscribe> list = subscribeMapper.selectList(new LambdaQueryWrapper<MessageSubscribe>()
                .eq(MessageSubscribe::getMemberId, memberId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (MessageSubscribe s : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("wxTemplateId", s.getWxTemplateId());
            m.put("tplCode", s.getTplCode());
            m.put("remainCount", s.getRemainCount());
            m.put("totalCount", s.getTotalCount());
            m.put("lastGrantAt", s.getLastGrantAt());
            out.add(m);
        }
        return out;
    }

    /* ==================== 4. 查询与已读 ==================== */

    public PageResult<Map<String, Object>> pageRecords(Long memberId, String tplCode, Integer status,
                                                       String keyword, int pageNum, int pageSize) {
        LambdaQueryWrapper<MessageRecord> q = new LambdaQueryWrapper<MessageRecord>()
                .eq(memberId != null, MessageRecord::getMemberId, memberId)
                .eq(tplCode != null && !tplCode.isBlank(), MessageRecord::getTplCode, tplCode)
                .eq(status != null, MessageRecord::getStatus, status)
                .and(keyword != null && !keyword.isBlank(),
                        w -> w.like(MessageRecord::getContent, keyword)
                                .or().like(MessageRecord::getBizNo, keyword))
                .orderByDesc(MessageRecord::getId);
        Page<MessageRecord> page = recordMapper.selectPage(new Page<>(pageNum, pageSize), q);
        return PageResult.of(page, this::toVo);
    }

    /** 小程序消息中心（站内信 + 已下发的订阅消息都可见） */
    public PageResult<Map<String, Object>> myMessages(Long memberId, Integer status, int pageNum, int pageSize) {
        return pageRecords(memberId, null, status, null, pageNum, pageSize);
    }

    public long unreadCount(Long memberId) {
        return recordMapper.selectCount(new LambdaQueryWrapper<MessageRecord>()
                .eq(MessageRecord::getMemberId, memberId)
                .in(MessageRecord::getStatus, MessageRecord.ST_SENT, MessageRecord.ST_PENDING));
    }

    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long memberId, Long recordId) {
        MessageRecord record = recordMapper.selectById(recordId);
        if (record == null || !record.getMemberId().equals(memberId)) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "消息不存在");
        }
        if (record.getStatus() != null && record.getStatus() == MessageRecord.ST_READ) {
            return;
        }
        record.setStatus(MessageRecord.ST_READ);
        record.setReadAt(LocalDateTime.now());
        recordMapper.updateById(record);
    }

    @Transactional(rollbackFor = Exception.class)
    public int markAllRead(Long memberId) {
        MessageRecord patch = new MessageRecord();
        patch.setStatus(MessageRecord.ST_READ);
        patch.setReadAt(LocalDateTime.now());
        return recordMapper.update(patch, new LambdaQueryWrapper<MessageRecord>()
                .eq(MessageRecord::getMemberId, memberId)
                .in(MessageRecord::getStatus, MessageRecord.ST_SENT, MessageRecord.ST_PENDING));
    }

    /** 渠道诊断（管理端展示「微信订阅消息是否就绪」） */
    public List<Map<String, Object>> channelStatus() {
        return channelRegistry.status();
    }

    /* ==================== 5. 渲染工具 ==================== */

    private Map<String, Object> buildContext(Member member, Map<String, Object> vars, MessageTpl tpl) {
        Map<String, Object> ctx = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();
        ctx.put("date", now.format(DATE_FMT));
        ctx.put("datetime", now.format(DATETIME_FMT));
        ctx.put("time", now.format(DATETIME_FMT));
        ctx.put("tplName", tpl.getTplName());
        if (member != null) {
            String name = member.getRealName() != null && !member.getRealName().isBlank()
                    ? member.getRealName()
                    : (member.getNickname() == null ? "会员" : member.getNickname());
            ctx.put("memberName", name);
            ctx.put("memberNo", member.getMemberNo());
            ctx.put("phone", member.getPhone() == null ? "" : MaskUtil.phone(member.getPhone()));
            ctx.put("levelId", member.getLevelId());
        } else {
            ctx.put("memberName", "会员");
            ctx.put("memberNo", "");
            ctx.put("phone", "");
        }
        if (vars != null) {
            vars.forEach((k, v) -> {
                if (k != null) {
                    ctx.put(k, v == null ? "" : v);
                }
            });
        }
        return ctx;
    }

    /** 渲染 {@code {key}} 占位符；未知占位符替换为空串（避免把模板语法暴露给会员） */
    static String render(String template, Map<String, Object> ctx) {
        if (template == null || template.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(template.length() + 32);
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c != '{') {
                sb.append(c);
                i++;
                continue;
            }
            int end = template.indexOf('}', i + 1);
            if (end < 0) {
                sb.append(template.substring(i));
                break;
            }
            String key = template.substring(i + 1, end).trim();
            Object v = ctx.get(key);
            if (v == null && key.startsWith("member.")) {
                v = ctx.get(key.substring("member.".length()));
            }
            sb.append(v == null ? "" : String.valueOf(v));
            i = end + 1;
        }
        return sb.toString();
    }

    static List<String> extractPlaceholders(String template) {
        List<String> out = new ArrayList<>();
        if (template == null) {
            return out;
        }
        int i = 0;
        while (i < template.length()) {
            int start = template.indexOf('{', i);
            if (start < 0) {
                break;
            }
            int end = template.indexOf('}', start + 1);
            if (end < 0) {
                break;
            }
            String key = template.substring(start + 1, end).trim();
            if (!key.isEmpty() && !out.contains(key)) {
                out.add(key);
            }
            i = end + 1;
        }
        return out;
    }

    private Map<String, Object> toVo(MessageRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("tplCode", r.getTplCode());
        m.put("channel", r.getChannel());
        m.put("channelText", MessageTpl.channelText(r.getChannel()));
        m.put("memberId", r.getMemberId());
        m.put("openid", r.getOpenid() == null ? null : MaskUtil.openid(r.getOpenid()));
        m.put("title", r.getTitle());
        m.put("content", r.getContent());
        m.put("bizNo", r.getBizNo());
        m.put("status", r.getStatus());
        m.put("statusText", MessageRecord.statusText(r.getStatus()));
        m.put("retryCount", r.getRetryCount());
        m.put("nextRetryAt", r.getNextRetryAt());
        m.put("errorMsg", r.getErrorMsg());
        m.put("sentAt", r.getSentAt());
        m.put("readAt", r.getReadAt());
        m.put("createdAt", r.getCreatedAt());
        return m;
    }

    private void validateJson(String json) {
        try {
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "wx_data_map 不是合法 JSON：" + e.getMessage());
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static int intVal(Object v, int def) {
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String clip(String v, int max) {
        if (v == null) {
            return null;
        }
        return v.length() <= max ? v : v.substring(0, max);
    }

    /** 供业务侧读取：今天的日期字符串（模板变量常用） */
    public static String today() {
        return LocalDate.now().format(DATE_FMT);
    }
}
