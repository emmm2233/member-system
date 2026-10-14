package com.company.member.modules.account.service;

import com.company.member.common.util.BizNoGenerator;
import com.company.member.common.util.MoneyUtil;
import com.company.member.modules.account.dto.AccountChangeCmd;
import com.company.member.modules.account.entity.MemberAccount;
import com.company.member.modules.account.entity.PointBatch;
import com.company.member.modules.account.mapper.MemberAccountMapper;
import com.company.member.modules.account.mapper.PointBatchMapper;
import com.company.member.modules.account.mapper.PointFlowMapper;
import com.company.member.modules.account.entity.PointFlow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 积分过期服务（FIFO 批次）
 *
 * <p>规则：
 * <ol>
 *   <li>扫描到期批次（分片游标，避免长事务）</li>
 *   <li>按会员汇总过期积分，账户积分扣减（不可为负，扣到 0 为止）</li>
 *   <li>写过期流水（biz_type=4）</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointExpireService {

    private final PointBatchMapper batchMapper;
    private final MemberAccountMapper accountMapper;
    private final PointFlowMapper pointFlowMapper;
    private final BizNoGenerator noGenerator;

    /** 处理一批到期积分，返回处理的批次数 */
    @Transactional(rollbackFor = Exception.class)
    public int expireBatches(int limit) {
        int processed = 0;
        long lastId = 0L;
        LocalDateTime now = LocalDateTime.now();
        while (true) {
            List<PointBatch> batches = batchMapper.selectExpired(now, lastId, limit);
            if (batches.isEmpty()) {
                break;
            }
            for (PointBatch batch : batches) {
                lastId = batch.getId();
                try {
                    expireOne(batch);
                    processed++;
                } catch (Exception e) {
                    log.error("[积分过期] 批次处理失败 batchId={}", batch.getId(), e);
                }
            }
            if (batches.size() < limit) {
                break;
            }
        }
        return processed;
    }

    private void expireOne(PointBatch batch) {
        long points = batch.getRemainPoints() == null ? 0L : batch.getRemainPoints();
        if (points <= 0) {
            batchMapper.expire(batch.getId());
            return;
        }
        // 1. 批次置过期
        int rows = batchMapper.expire(batch.getId());
        if (rows == 0) {
            return;
        }
        // 2. 账户扣减（行锁，扣到 0 为止）
        MemberAccount acc = accountMapper.selectForUpdate(batch.getMemberId());
        if (acc == null) {
            log.warn("[积分过期] 账户不存在 memberId={}", batch.getMemberId());
            return;
        }
        long before = acc.getPoints() == null ? 0L : acc.getPoints();
        long deduct = Math.min(points, Math.max(before, 0L));
        if (deduct > 0) {
            int updated = accountMapper.changePoints(acc.getId(), -deduct, acc.getVersion());
            if (updated == 0) {
                log.warn("[积分过期] 账户更新冲突，跳过 batchId={}", batch.getId());
                return;
            }
            // 3. 写过期流水
            PointFlow flow = new PointFlow();
            flow.setFlowNo(noGenerator.pointFlowNo());
            flow.setMemberId(batch.getMemberId());
            flow.setBizType(AccountService.PBIZ_EXPIRE);
            flow.setChangePoints(-deduct);
            flow.setBeforePoints(before);
            flow.setAfterPoints(before - deduct);
            flow.setBatchId(batch.getId());
            flow.setIdempotencyKey("expire:batch:" + batch.getId());
            flow.setRemark("积分到期自动过期");
            flow.setOccurTime(LocalDateTime.now());
            pointFlowMapper.insert(flow);
        }
        if (deduct < points) {
            log.warn("[积分过期] 账户积分不足以扣减全部到期积分 memberId={}, 到期={}, 实际扣减={}",
                    batch.getMemberId(), points, deduct);
        }
    }

    /** 手动触发（运维/测试） */
    public int expireNow() {
        return expireBatches(500);
    }

    /** 即将过期积分汇总（提醒用） */
    public long expiringSoon(Long memberId, int days) {
        List<PointBatch> list = batchMapper.selectExpiringSoon(memberId, LocalDateTime.now().plusDays(days));
        return list.stream().mapToLong(b -> b.getRemainPoints() == null ? 0L : b.getRemainPoints()).sum();
    }

    /** 格式化金额辅助（保留工具类引用，便于后续扩展） */
    static String fmt(java.math.BigDecimal v) {
        return MoneyUtil.scale(v).toPlainString();
    }
}
