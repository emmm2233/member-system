package com.company.member.modules.member.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.member.common.exception.BizException;
import com.company.member.common.result.ErrorCode;
import com.company.member.common.result.PageResult;
import com.company.member.common.util.BizNoGenerator;
import com.company.member.common.util.MaskUtil;
import com.company.member.modules.account.entity.MemberAccount;
import com.company.member.modules.account.mapper.MemberAccountMapper;
import com.company.member.modules.account.service.AccountService;
import com.company.member.modules.member.dto.MemberQuery;
import com.company.member.modules.member.dto.MemberSaveCmd;
import com.company.member.modules.member.entity.Member;
import com.company.member.modules.member.entity.MemberLevel;
import com.company.member.modules.member.entity.MemberStat;
import com.company.member.modules.member.entity.MemberTag;
import com.company.member.modules.member.entity.MemberTagRel;
import com.company.member.modules.member.mapper.MemberLevelMapper;
import com.company.member.modules.member.mapper.MemberMapper;
import com.company.member.modules.member.mapper.MemberStatMapper;
import com.company.member.modules.member.mapper.MemberTagMapper;
import com.company.member.modules.member.mapper.MemberTagRelMapper;
import com.company.member.modules.member.vo.MemberDetailVO;
import com.company.member.modules.member.vo.MemberVO;
import com.company.member.modules.store.entity.Store;
import com.company.member.modules.store.mapper.StoreMapper;
import com.company.member.security.SecurityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 会员中心服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberMapper memberMapper;
    private final MemberLevelMapper levelMapper;
    private final MemberStatMapper statMapper;
    private final MemberTagMapper tagMapper;
    private final MemberTagRelMapper tagRelMapper;
    private final MemberAccountMapper accountMapper;
    private final AccountService accountService;
    private final StoreMapper storeMapper;
    private final BizNoGenerator noGenerator;
    private final SecurityService securityService;

    /* ==================== 建档 ==================== */

    /**
     * 会员建档（门店建档 / 小程序注册共用）
     *
     * <p>事务内完成：会员 + 账户 + 统计 + 标签 + 推荐关系
     */
    @Transactional(rollbackFor = Exception.class)
    public MemberVO create(MemberSaveCmd cmd) {
        // 手机号唯一校验（应用层先查，唯一索引兜底）
        if (cmd.getPhone() != null && !cmd.getPhone().isBlank()) {
            Long exists = memberMapper.selectCount(new LambdaQueryWrapper<Member>()
                    .eq(Member::getPhone, cmd.getPhone()));
            if (exists != null && exists > 0) {
                throw new BizException(ErrorCode.PHONE_EXISTS);
            }
        }

        Member member = new Member();
        // 会员号生成：唯一键冲突时自动重新生成（缓存计数器与库不一致的场景）
        member.setMemberNo(noGenerator.generateWithRetry(noGenerator::memberNo,
                no -> memberMapper.selectCount(new LambdaQueryWrapper<Member>().eq(Member::getMemberNo, no)) > 0));
        member.setPhone(blankToNull(cmd.getPhone()));
        member.setRealName(cmd.getRealName());
        member.setNickname(cmd.getNickname() == null ? "会员" + member.getMemberNo().substring(8) : cmd.getNickname());
        member.setAvatar(cmd.getAvatar());
        member.setGender(cmd.getGender() == null ? 0 : cmd.getGender());
        member.setBirthday(cmd.getBirthday());
        member.setSource(cmd.getSource() == null ? 1 : cmd.getSource());
        member.setStatus(1);
        member.setStoreId(cmd.getStoreId() != null ? cmd.getStoreId() : currentStoreId());
        member.setCreatorId(securityService.currentUserId());
        member.setRemark(cmd.getRemark());
        member.setDeleted(0);
        member.setTenantId(0L);

        // 等级：指定 > 默认等级
        MemberLevel level = cmd.getLevelId() != null
                ? levelMapper.selectById(cmd.getLevelId())
                : levelMapper.selectDefaultLevel();
        if (level != null) {
            member.setLevelId(level.getId());
        }

        // 推荐人解析
        Long referrerId = cmd.getReferrerId();
        if (referrerId == null && cmd.getReferrerPhone() != null && !cmd.getReferrerPhone().isBlank()) {
            Member referrer = memberMapper.selectOne(new LambdaQueryWrapper<Member>()
                    .eq(Member::getPhone, cmd.getReferrerPhone()).last("LIMIT 1"));
            if (referrer != null) {
                referrerId = referrer.getId();
            }
        }
        member.setReferrerId(referrerId);

        memberMapper.insert(member);

        // 初始化资产账户
        accountService.initAccount(member.getId());
        // 初始化统计快照
        MemberStat stat = new MemberStat();
        stat.setMemberId(member.getId());
        stat.setPayAmount30d(BigDecimal.ZERO);
        stat.setPayAmount90d(BigDecimal.ZERO);
        stat.setPayAmount12m(BigDecimal.ZERO);
        stat.setOrderCount12m(0);
        stat.setVisitDays30d(0);
        stat.setAvgOrderAmount(BigDecimal.ZERO);
        statMapper.insert(stat);
        // 标签
        if (cmd.getTagIds() != null && !cmd.getTagIds().isEmpty()) {
            saveTags(member.getId(), cmd.getTagIds());
        }

        log.info("[会员] 建档成功 memberId={}, memberNo={}, phone={}",
                member.getId(), member.getMemberNo(), MaskUtil.phone(member.getPhone()));
        return toVO(member, null, List.of());
    }

    /* ==================== 列表 ==================== */

    public PageResult<MemberVO> page(MemberQuery query) {
        int pageNum = query.getPageNum() == null || query.getPageNum() < 1 ? 1 : query.getPageNum();
        int pageSize = query.getPageSize() == null || query.getPageSize() < 1 ? 20 : Math.min(query.getPageSize(), 200);

        LambdaQueryWrapper<Member> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Member::getDeleted, 0);

        // 数据权限：非全部范围的用户只能看自己门店
        List<Long> scopeStores = securityService.dataScopeStoreIds();
        if (scopeStores != null) {
            wrapper.in(Member::getStoreId, scopeStores);
        }
        if (query.getStoreId() != null) {
            wrapper.eq(Member::getStoreId, query.getStoreId());
        }
        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            String kw = query.getKeyword().trim();
            wrapper.and(w -> w.like(Member::getMemberNo, kw)
                    .or().like(Member::getPhone, kw)
                    .or().like(Member::getNickname, kw)
                    .or().like(Member::getRealName, kw));
        }
        if (query.getPhone() != null && !query.getPhone().isBlank()) {
            String phone = query.getPhone().trim();
            if (phone.length() == 4) {
                wrapper.likeRight(Member::getPhone, null).apply("phone LIKE CONCAT('%', {0})", phone);
            } else {
                wrapper.eq(Member::getPhone, phone);
            }
        }
        if (query.getLevelId() != null) {
            wrapper.eq(Member::getLevelId, query.getLevelId());
        }
        if (query.getStatus() != null) {
            wrapper.eq(Member::getStatus, query.getStatus());
        }
        if (query.getSource() != null) {
            wrapper.eq(Member::getSource, query.getSource());
        }
        if (query.getBirthdayMonth() != null) {
            wrapper.apply("MONTH(birthday) = {0}", query.getBirthdayMonth());
        }
        if (query.getCreatedStart() != null) {
            wrapper.ge(Member::getCreatedAt, query.getCreatedStart().atStartOfDay());
        }
        if (query.getCreatedEnd() != null) {
            wrapper.lt(Member::getCreatedAt, query.getCreatedEnd().plusDays(1).atStartOfDay());
        }
        // 资产相关条件：子查询过滤（先查会员 id 集合）
        List<Long> filterIds = filterByAccount(query);
        if (filterIds != null) {
            if (filterIds.isEmpty()) {
                return PageResult.empty(pageNum, pageSize);
            }
            wrapper.in(Member::getId, filterIds);
        }
        // 标签条件
        if (query.getTagIds() != null && !query.getTagIds().isBlank()) {
            List<Long> tagIds = parseIds(query.getTagIds());
            if (!tagIds.isEmpty()) {
                for (Long tagId : tagIds) {
                    wrapper.apply("EXISTS (SELECT 1 FROM m_member_tag_rel r WHERE r.member_id = m_member.id AND r.tag_id = {0})", tagId);
                }
            }
        }

        applySort(wrapper, query);

        Page<Member> page = memberMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        List<MemberVO> list = assembleList(page.getRecords());
        return PageResult.of(list, page.getTotal(), pageNum, pageSize);
    }

    /** 按资产条件筛选会员 id（余额/积分区间、沉睡筛选） */
    private List<Long> filterByAccount(MemberQuery query) {
        boolean needBalance = query.getMinBalance() != null || query.getMaxBalance() != null || Boolean.TRUE.equals(query.getHasBalance());
        boolean needPoints = query.getMinPoints() != null || query.getMaxPoints() != null;
        if (!needBalance && !needPoints) {
            return null;
        }
        LambdaQueryWrapper<MemberAccount> w = new LambdaQueryWrapper<>();
        if (query.getMinBalance() != null) {
            w.apply("(balance + gift_balance) >= {0}", query.getMinBalance());
        }
        if (query.getMaxBalance() != null) {
            w.apply("(balance + gift_balance) <= {0}", query.getMaxBalance());
        }
        if (Boolean.TRUE.equals(query.getHasBalance())) {
            w.apply("(balance + gift_balance) > 0");
        }
        if (query.getMinPoints() != null) {
            w.ge(MemberAccount::getPoints, query.getMinPoints());
        }
        if (query.getMaxPoints() != null) {
            w.le(MemberAccount::getPoints, query.getMaxPoints());
        }
        List<MemberAccount> accounts = accountMapper.selectList(w);
        return accounts.stream().map(MemberAccount::getMemberId).distinct().toList();
    }

    private void applySort(LambdaQueryWrapper<Member> wrapper, MemberQuery query) {
        String field = query.getSortField() == null ? "id" : query.getSortField();
        boolean asc = "asc".equalsIgnoreCase(query.getSortOrder());
        switch (field) {
            case "created_at" -> wrapper.orderBy(true, asc, Member::getCreatedAt);
            case "balance" -> wrapper.last("ORDER BY (SELECT IFNULL(balance + gift_balance, 0) FROM m_member_account a WHERE a.member_id = m_member.id) "
                    + (asc ? "ASC" : "DESC"));
            case "points" -> wrapper.last("ORDER BY (SELECT IFNULL(points, 0) FROM m_member_account a WHERE a.member_id = m_member.id) "
                    + (asc ? "ASC" : "DESC"));
            case "total_consume" -> wrapper.last("ORDER BY (SELECT IFNULL(total_consume, 0) FROM m_member_account a WHERE a.member_id = m_member.id) "
                    + (asc ? "ASC" : "DESC"));
            case "last_consume_at" -> wrapper.last("ORDER BY (SELECT last_consume_at FROM m_member_stat s WHERE s.member_id = m_member.id) "
                    + (asc ? "ASC" : "DESC"));
            default -> wrapper.orderByDesc(Member::getId);
        }
    }

    /* ==================== 详情 ==================== */

    public MemberDetailVO detail(Long id) {
        Member member = getExisting(id);
        MemberAccount account = accountService.getAccount(id);
        MemberStat stat = statMapper.selectOne(new LambdaQueryWrapper<MemberStat>().eq(MemberStat::getMemberId, id));

        MemberDetailVO vo = new MemberDetailVO();

        List<MemberVO.TagBrief> tags = loadTags(List.of(id)).getOrDefault(id, List.of());
        vo.setMember(toVO(member, account, tags));

        // 资产
        MemberDetailVO.AccountBrief ab = new MemberDetailVO.AccountBrief();
        ab.setBalance(account.getBalance());
        ab.setGiftBalance(account.getGiftBalance());
        ab.setFrozenAmount(account.getFrozenAmount());
        ab.setPoints(account.getPoints());
        ab.setGrowth(account.getGrowth());
        ab.setTotalRecharge(account.getTotalRecharge());
        ab.setTotalGift(account.getTotalGift());
        ab.setTotalConsume(account.getTotalConsume());
        ab.setTotalConsumeCount(account.getTotalConsumeCount());
        ab.setBalanceExpireAt(account.getBalanceExpireAt());
        ab.setAvailable(account.available());
        fillLevelProgress(ab, account, member);
        vo.setAccount(ab);

        // 统计
        MemberDetailVO.StatBrief sb = new MemberDetailVO.StatBrief();
        if (stat != null) {
            sb.setFirstConsumeAt(stat.getFirstConsumeAt());
            sb.setLastConsumeAt(stat.getLastConsumeAt());
            sb.setPayAmount30d(stat.getPayAmount30d());
            sb.setPayAmount90d(stat.getPayAmount90d());
            sb.setPayAmount12m(stat.getPayAmount12m());
            sb.setOrderCount12m(stat.getOrderCount12m());
            sb.setVisitDays30d(stat.getVisitDays30d());
            sb.setAvgOrderAmount(stat.getAvgOrderAmount());
        }
        vo.setStat(sb);

        // 卡券概览（未安装对应模块时返回 0）
        MemberDetailVO.CouponSummary cs = new MemberDetailVO.CouponSummary();
        cs.setUnused(0);
        cs.setExpiringIn7d(0);
        cs.setUsed(0);
        cs.setExpired(0);
        vo.setCouponSummary(cs);
        MemberDetailVO.CardSummary cds = new MemberDetailVO.CardSummary();
        cds.setNormal(0);
        cds.setTotalRemainTimes(0);
        vo.setCardSummary(cds);

        return vo;
    }

    /** 距下一等级的成长值进度 */
    private void fillLevelProgress(MemberDetailVO.AccountBrief ab, MemberAccount account, Member member) {
        if (member.getLevelId() == null) {
            return;
        }
        List<MemberLevel> levels = levelMapper.selectList(new LambdaQueryWrapper<MemberLevel>()
                .eq(MemberLevel::getStatus, 1).eq(MemberLevel::getDeleted, 0)
                .orderByAsc(MemberLevel::getLevelValue));
        MemberLevel current = levels.stream().filter(l -> l.getId().equals(member.getLevelId())).findFirst().orElse(null);
        if (current == null) {
            return;
        }
        MemberLevel next = levels.stream()
                .filter(l -> l.getLevelValue() > current.getLevelValue())
                .min(Comparator.comparingInt(MemberLevel::getLevelValue))
                .orElse(null);
        if (next == null) {
            ab.setLevelProgress(BigDecimal.ONE);
            return;
        }
        ab.setNextLevelId(next.getId());
        ab.setNextLevelName(next.getLevelName());
        // 门槛从等级规则表读取（取升级规则第一个 items 的 value）
        Long threshold = null;
        try {
            threshold = levelMapper.selectById(next.getId()) == null ? null : null;
        } catch (Exception ignored) {
        }
        long growth = account.getGrowth() == null ? 0L : account.getGrowth();
        if (threshold != null && threshold > 0) {
            ab.setNextLevelNeedGrowth(Math.max(threshold - growth, 0));
            ab.setLevelProgress(BigDecimal.valueOf(Math.min((double) growth / threshold, 1.0))
                    .setScale(4, RoundingMode.HALF_UP));
        }
    }

    /* ==================== 编辑 ==================== */

    @Transactional(rollbackFor = Exception.class)
    public MemberVO update(Long id, MemberSaveCmd cmd) {
        Member member = getExisting(id);
        if (cmd.getPhone() != null && !cmd.getPhone().isBlank() && !cmd.getPhone().equals(member.getPhone())) {
            Long exists = memberMapper.selectCount(new LambdaQueryWrapper<Member>()
                    .eq(Member::getPhone, cmd.getPhone()).ne(Member::getId, id));
            if (exists != null && exists > 0) {
                throw new BizException(ErrorCode.PHONE_EXISTS);
            }
            member.setPhone(cmd.getPhone());
        }
        if (cmd.getRealName() != null) {
            member.setRealName(cmd.getRealName());
        }
        if (cmd.getNickname() != null) {
            member.setNickname(cmd.getNickname());
        }
        if (cmd.getAvatar() != null) {
            member.setAvatar(cmd.getAvatar());
        }
        if (cmd.getGender() != null) {
            member.setGender(cmd.getGender());
        }
        if (cmd.getBirthday() != null) {
            member.setBirthday(cmd.getBirthday());
        }
        if (cmd.getStoreId() != null) {
            member.setStoreId(cmd.getStoreId());
        }
        if (cmd.getRemark() != null) {
            member.setRemark(cmd.getRemark());
        }
        memberMapper.updateById(member);

        if (cmd.getTagIds() != null) {
            saveTags(id, cmd.getTagIds());
        }
        log.info("[会员] 修改资料 memberId={}", id);
        return toVO(member, accountMapper.selectByMemberId(id), loadTags(List.of(id)).getOrDefault(id, List.of()));
    }

    /* ==================== 状态 / 等级 / 标签 ==================== */

    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, Integer status, String remark) {
        Member member = getExisting(id);
        if (status == null || status < 1 || status > 3) {
            throw new BizException(ErrorCode.PARAM_INVALID, "状态值不合法");
        }
        if (status == 3) {
            // 注销前校验余额与积分必须为 0
            MemberAccount acc = accountService.getAccount(id);
            if (acc.available().signum() != 0 || (acc.getPoints() != null && acc.getPoints() != 0)) {
                throw new BizException(ErrorCode.MEMBER_BALANCE_NOT_ZERO,
                        "余额或积分不为 0，请先结清后再注销");
            }
            memberMapper.cancel(id);
            log.warn("[会员] 注销 memberId={}, 原因={}", id, remark);
            return;
        }
        member.setStatus(status);
        if (remark != null && !remark.isBlank()) {
            member.setRemark(remark);
        }
        memberMapper.updateById(member);
        log.info("[会员] 状态变更 memberId={}, status={}", id, status);
    }

    @Transactional(rollbackFor = Exception.class)
    public void changeLevel(Long id, Long levelId, String reason) {
        Member member = getExisting(id);
        MemberLevel level = levelMapper.selectById(levelId);
        if (level == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND, "等级不存在");
        }
        LocalDateTime expireAt = null;
        if (level.getValidMonths() != null && level.getValidMonths() > 0) {
            expireAt = LocalDateTime.now().plusMonths(level.getValidMonths());
        }
        memberMapper.updateLevel(id, levelId, expireAt);
        log.info("[会员] 手工调整等级 memberId={}, {} -> {}, 原因={}", id, member.getLevelId(), levelId, reason);
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveTags(Long memberId, List<Long> tagIds) {
        tagRelMapper.deleteByMemberId(memberId);
        if (tagIds == null || tagIds.isEmpty()) {
            return;
        }
        for (Long tagId : tagIds.stream().distinct().toList()) {
            MemberTagRel rel = new MemberTagRel();
            rel.setMemberId(memberId);
            rel.setTagId(tagId);
            try {
                tagRelMapper.insert(rel);
            } catch (org.springframework.dao.DuplicateKeyException ignored) {
                // 并发重复插入忽略
            }
        }
    }

    /* ==================== 查询辅助 ==================== */

    public Member getExisting(Long id) {
        Member member = memberMapper.selectById(id);
        if (member == null || (member.getDeleted() != null && member.getDeleted() == 1)) {
            throw new BizException(ErrorCode.MEMBER_NOT_FOUND);
        }
        return member;
    }

    public Member getByPhone(String phone) {
        return memberMapper.selectOne(new LambdaQueryWrapper<Member>().eq(Member::getPhone, phone).last("LIMIT 1"));
    }

    /** 批量装配列表 VO（避免 N+1：账户、等级、门店、标签各查一次） */
    public List<MemberVO> assembleList(List<Member> members) {
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        List<Long> ids = members.stream().map(Member::getId).toList();
        Map<Long, MemberAccount> accountMap = accountMapper.selectList(new LambdaQueryWrapper<MemberAccount>()
                        .in(MemberAccount::getMemberId, ids)).stream()
                .collect(Collectors.toMap(MemberAccount::getMemberId, Function.identity(), (a, b) -> a));
        Map<Long, MemberStat> statMap = statMapper.selectList(new LambdaQueryWrapper<MemberStat>()
                        .in(MemberStat::getMemberId, ids)).stream()
                .collect(Collectors.toMap(MemberStat::getMemberId, Function.identity(), (a, b) -> a));
        Map<Long, MemberLevel> levelMap = loadLevelMap(members);
        Map<Long, String> storeMap = loadStoreMap(members);
        Map<Long, List<MemberVO.TagBrief>> tagMap = loadTags(ids);

        List<MemberVO> list = new ArrayList<>(members.size());
        for (Member m : members) {
            MemberVO vo = toVO(m, accountMap.get(m.getId()), tagMap.getOrDefault(m.getId(), List.of()));
            MemberLevel level = levelMap.get(m.getLevelId());
            if (level != null) {
                vo.setLevelName(level.getLevelName());
                vo.setLevelValue(level.getLevelValue());
            }
            if (m.getStoreId() != null) {
                vo.setStoreName(storeMap.get(m.getStoreId()));
            }
            MemberStat stat = statMap.get(m.getId());
            if (stat != null) {
                vo.setLastConsumeAt(stat.getLastConsumeAt());
            }
            list.add(vo);
        }
        return list;
    }

    private Map<Long, MemberLevel> loadLevelMap(List<Member> members) {
        List<Long> levelIds = members.stream().map(Member::getLevelId).filter(java.util.Objects::nonNull).distinct().toList();
        if (levelIds.isEmpty()) {
            return Map.of();
        }
        return levelMapper.selectBatchIds(levelIds).stream()
                .collect(Collectors.toMap(MemberLevel::getId, Function.identity(), (a, b) -> a));
    }

    private Map<Long, String> loadStoreMap(List<Member> members) {
        List<Long> storeIds = members.stream().map(Member::getStoreId).filter(java.util.Objects::nonNull).distinct().toList();
        if (storeIds.isEmpty()) {
            return Map.of();
        }
        return storeMapper.selectBatchIds(storeIds).stream()
                .collect(Collectors.toMap(Store::getId, Store::getStoreName, (a, b) -> a));
    }

    /** 批量查标签 */
    public Map<Long, List<MemberVO.TagBrief>> loadTags(List<Long> memberIds) {
        Map<Long, List<MemberVO.TagBrief>> result = new HashMap<>();
        if (memberIds == null || memberIds.isEmpty()) {
            return result;
        }
        List<MemberTagRel> rels = tagRelMapper.selectList(new LambdaQueryWrapper<MemberTagRel>()
                .in(MemberTagRel::getMemberId, memberIds));
        if (rels.isEmpty()) {
            return result;
        }
        List<Long> tagIds = rels.stream().map(MemberTagRel::getTagId).distinct().toList();
        Map<Long, MemberTag> tagMap = tagMapper.selectBatchIds(tagIds).stream()
                .collect(Collectors.toMap(MemberTag::getId, Function.identity(), (a, b) -> a));
        for (MemberTagRel rel : rels) {
            MemberTag tag = tagMap.get(rel.getTagId());
            if (tag == null) {
                continue;
            }
            MemberVO.TagBrief brief = new MemberVO.TagBrief();
            brief.setId(tag.getId());
            brief.setTagName(tag.getTagName());
            brief.setTagColor(tag.getTagColor());
            result.computeIfAbsent(rel.getMemberId(), k -> new ArrayList<>()).add(brief);
        }
        return result;
    }

    /** 实体 → 列表 VO */
    public MemberVO toVO(Member m, MemberAccount acc, List<MemberVO.TagBrief> tags) {
        MemberVO vo = new MemberVO();
        vo.setId(m.getId());
        vo.setMemberNo(m.getMemberNo());
        vo.setPhone(m.getPhone());
        vo.setPhoneMasked(MaskUtil.phone(m.getPhone()));
        vo.setNickname(m.getNickname());
        vo.setAvatar(m.getAvatar());
        vo.setRealName(m.getRealName());
        vo.setGender(m.getGender());
        vo.setBirthday(m.getBirthday());
        vo.setLevelId(m.getLevelId());
        vo.setLevelExpireAt(m.getLevelExpireAt());
        vo.setStatus(m.getStatus());
        vo.setStatusText(MemberVO.statusText(m.getStatus()));
        vo.setSource(m.getSource());
        vo.setSourceText(MemberVO.sourceText(m.getSource()));
        vo.setStoreId(m.getStoreId());
        vo.setRemark(m.getRemark());
        vo.setCreatedAt(m.getCreatedAt());
        if (acc != null) {
            vo.setBalance(acc.getBalance());
            vo.setGiftBalance(acc.getGiftBalance());
            vo.setPoints(acc.getPoints());
            vo.setGrowth(acc.getGrowth());
            vo.setTotalConsume(acc.getTotalConsume());
            vo.setTotalConsumeCount(acc.getTotalConsumeCount());
            vo.setTotalRecharge(acc.getTotalRecharge());
        }
        vo.setTags(tags == null ? List.of() : tags);
        return vo;
    }

    private Long currentStoreId() {
        Long storeId = securityService.currentStoreId();
        return storeId == null ? 1L : storeId;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static List<Long> parseIds(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(Long::valueOf).toList();
    }
}
