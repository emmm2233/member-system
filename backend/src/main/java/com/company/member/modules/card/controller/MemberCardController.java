package com.company.member.modules.card.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.member.common.annotation.NoAudit;
import com.company.member.common.annotation.OpAudit;
import com.company.member.common.annotation.Idempotent;
import com.company.member.common.result.PageResult;
import com.company.member.common.result.R;
import com.company.member.modules.card.entity.CardProduct;
import com.company.member.modules.card.entity.CardVerifyLog;
import com.company.member.modules.card.mapper.CardProductMapper;
import com.company.member.modules.card.mapper.CardVerifyLogMapper;
import com.company.member.modules.card.service.MemberCardService;
import com.company.member.modules.store.mapper.StoreMapper;
import com.company.member.security.SecurityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端 · 次卡
 */
@Tag(name = "管理端-次卡")
@RestController
@RequestMapping("/api/admin/cards")
@RequiredArgsConstructor
public class MemberCardController {

    private final MemberCardService cardService;
    private final CardProductMapper productMapper;
    private final CardVerifyLogMapper verifyLogMapper;
    private final StoreMapper storeMapper;
    private final SecurityService securityService;

    /* ---------- 卡产品 ---------- */

    @Operation(summary = "卡产品列表")
    @GetMapping("/products")
    @PreAuthorize("@ps.hasAny('card:list','card:sell')")
    public R<List<CardProduct>> products(@RequestParam(defaultValue = "true") boolean onlyEnabled) {
        return R.ok(productMapper.selectList(new LambdaQueryWrapper<CardProduct>()
                .eq(CardProduct::getDeleted, 0)
                .eq(onlyEnabled, CardProduct::getStatus, 1)
                .orderByAsc(CardProduct::getSortNo)));
    }

    @Operation(summary = "新增卡产品")
    @PostMapping("/products")
    @PreAuthorize("@ps.has('card:add')")
    public R<CardProduct> createProduct(@RequestBody CardProduct product) {
        product.setId(null);
        product.setDeleted(0);
        if (product.getStatus() == null) {
            product.setStatus(1);
        }
        if (product.getAllowRefund() == null) {
            product.setAllowRefund(0);
        }
        productMapper.insert(product);
        return R.ok(product);
    }

    @Operation(summary = "修改卡产品")
    @PostMapping("/products/{id}")
    @PreAuthorize("@ps.has('card:edit')")
    public R<CardProduct> updateProduct(@PathVariable Long id, @RequestBody CardProduct product) {
        product.setId(id);
        productMapper.updateById(product);
        return R.ok(productMapper.selectById(id));
    }

    /* ---------- 售卡 ---------- */

    @Operation(summary = "售卡")
    @PostMapping("/sell")
    @PreAuthorize("@ps.has('card:sell')")
    @Idempotent(prefix = "card-sell", desc = "售卡正在处理中，请勿重复提交")
    @OpAudit(opType = "SALE", desc = "次卡售卡")
    public R<Map<String, Object>> sell(@RequestBody Map<String, Object> body) {
        Long memberId = Long.valueOf(String.valueOf(body.get("memberId")));
        Long cardProductId = Long.valueOf(String.valueOf(body.get("cardProductId")));
        Integer payMethod = body.get("payMethod") == null ? 2 : Integer.valueOf(String.valueOf(body.get("payMethod")));
        Long storeId = body.get("storeId") == null ? securityService.currentStoreId()
                : Long.valueOf(String.valueOf(body.get("storeId")));
        String outTradeNo = body.get("outTradeNo") == null ? null : String.valueOf(body.get("outTradeNo"));
        return R.ok(cardService.sell(memberId, cardProductId, payMethod, storeId,
                securityService.currentUserId(), outTradeNo, null));
    }

    /* ---------- 核销 ---------- */

    @Operation(summary = "次卡核销")
    @PostMapping("/{cardNo}/verify")
    @PreAuthorize("@ps.has('card:verify')")
    @Idempotent(prefix = "card-verify", desc = "核销正在处理中，请勿重复提交")
    @OpAudit(opType = "VERIFY", desc = "次卡核销")
    public R<Map<String, Object>> verify(@PathVariable String cardNo, @RequestBody Map<String, Object> body) {
        Long itemId = body.get("itemId") == null ? null : Long.valueOf(String.valueOf(body.get("itemId")));
        int times = body.get("times") == null ? 1 : Integer.parseInt(String.valueOf(body.get("times")));
        Long storeId = body.get("storeId") == null ? securityService.currentStoreId()
                : Long.valueOf(String.valueOf(body.get("storeId")));
        String remark = body.get("remark") == null ? null : String.valueOf(body.get("remark"));
        return R.ok(cardService.verify(cardNo, itemId, times, storeId,
                securityService.currentUserId(), remark, null));
    }

    @Operation(summary = "次卡冻结/解冻")
    @PostMapping("/{cardNo}/freeze")
    @PreAuthorize("@ps.has('card:edit')")
    @OpAudit(opType = "UPDATE", desc = "次卡冻结解冻")
    public R<Void> freeze(@PathVariable String cardNo,
                          @RequestParam boolean freeze,
                          @RequestParam(required = false) String remark) {
        cardService.freeze(cardNo, freeze, securityService.currentUserId(), remark);
        return R.ok();
    }

    @Operation(summary = "次卡延期")
    @PostMapping("/{cardNo}/extend")
    @PreAuthorize("@ps.has('card:edit')")
    @OpAudit(opType = "UPDATE", desc = "次卡延期")
    public R<Void> extend(@PathVariable String cardNo,
                          @RequestParam int days,
                          @RequestParam(required = false) String reason) {
        cardService.extend(cardNo, days, securityService.currentUserId(), reason);
        return R.ok();
    }

    @Operation(summary = "次卡退卡（按剩余次数折算）")
    @PostMapping("/{cardNo}/refund")
    @PreAuthorize("@ps.has('card:refund')")
    @OpAudit(opType = "REFUND", desc = "次卡退卡")
    @Idempotent(prefix = "card-refund", desc = "该次卡退卡正在处理中，请勿重复提交")
    public R<Map<String, Object>> refund(@PathVariable String cardNo,
                                         @RequestParam(required = false) String reason) {
        return R.ok(cardService.refund(cardNo, securityService.currentUserId(), reason));
    }

    /* ---------- 查询 ---------- */

    @Operation(summary = "会员持卡列表")
    @GetMapping("/members")
    @PreAuthorize("@ps.has('card:list')")
    public R<List<Map<String, Object>>> memberCards(@RequestParam Long memberId,
                                                    @RequestParam(required = false) Integer status) {
        return R.ok(cardService.memberCards(memberId, status));
    }

    @Operation(summary = "次卡详情（含权益项与核销记录）")
    @GetMapping("/members/{cardNo}")
    @PreAuthorize("@ps.has('card:query')")
    public R<Map<String, Object>> cardDetail(@PathVariable String cardNo) {
        return R.ok(cardService.cardDetail(cardNo));
    }

    @Operation(summary = "次卡核销记录分页")
    @GetMapping("/verify-logs")
    @PreAuthorize("@ps.has('card:list')")
    public R<PageResult<Map<String, Object>>> verifyLogs(@RequestParam(required = false) String cardNo,
                                                         @RequestParam(required = false) Long memberId,
                                                         @RequestParam(required = false) Long storeId,
                                                         @RequestParam(defaultValue = "1") int pageNum,
                                                         @RequestParam(defaultValue = "20") int pageSize) {
        LambdaQueryWrapper<CardVerifyLog> w = new LambdaQueryWrapper<CardVerifyLog>()
                .eq(cardNo != null && !cardNo.isBlank(), CardVerifyLog::getCardNo, cardNo)
                .eq(memberId != null, CardVerifyLog::getMemberId, memberId)
                .eq(storeId != null, CardVerifyLog::getStoreId, storeId)
                .orderByDesc(CardVerifyLog::getId);
        Page<CardVerifyLog> page = verifyLogMapper.selectPage(new Page<>(pageNum, Math.min(pageSize, 200)), w);

        Map<Long, String> storeNames = page.getRecords().isEmpty() ? Map.of()
                : storeMapper.selectBatchIds(page.getRecords().stream().map(CardVerifyLog::getStoreId)
                        .filter(java.util.Objects::nonNull).distinct().toList()).stream()
                .collect(java.util.stream.Collectors.toMap(s -> s.getId(), s -> s.getStoreName(), (a, b) -> a));

        List<Map<String, Object>> list = new ArrayList<>();
        for (CardVerifyLog l : page.getRecords()) {
            Map<String, Object> m = new HashMap<>();
            m.put("verifyNo", l.getVerifyNo());
            m.put("cardNo", l.getCardNo());
            m.put("itemName", l.getItemName());
            m.put("verifyTimes", l.getVerifyTimes());
            m.put("beforeTimes", l.getBeforeTimes());
            m.put("afterTimes", l.getAfterTimes());
            m.put("memberId", l.getMemberId());
            m.put("storeName", storeNames.get(l.getStoreId()));
            m.put("operatorId", l.getOperatorId());
            m.put("remark", l.getRemark());
            m.put("verifiedAt", l.getVerifiedAt());
            list.add(m);
        }
        return R.ok(PageResult.of(list, page.getTotal(), pageNum, page.getSize()));
    }

    @Operation(summary = "手动触发次卡过期检查（运维用）")
    @PostMapping("/expire-job")
    @PreAuthorize("@ps.has('card:edit')")
    @NoAudit("运维手动触发次卡过期任务：无人工操作语义")
    public R<Integer> expireJob() {
        return R.ok(cardService.expireCards(500));
    }
}
