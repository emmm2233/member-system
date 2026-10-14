package com.company.member.mp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.member.common.cache.CacheService;
import com.company.member.common.exception.BizException;
import com.company.member.common.result.ErrorCode;
import com.company.member.common.util.BizNoGenerator;
import com.company.member.modules.account.service.AccountService;
import com.company.member.modules.member.entity.Member;
import com.company.member.modules.member.entity.MemberLevel;
import com.company.member.modules.member.entity.MemberStat;
import com.company.member.modules.member.mapper.MemberLevelMapper;
import com.company.member.modules.member.mapper.MemberMapper;
import com.company.member.modules.member.mapper.MemberStatMapper;
import com.company.member.modules.system.service.LoginLogService;
import com.company.member.security.JwtTokenProvider;
import com.company.member.security.LoginUser;
import com.company.member.security.SecurityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 小程序端认证服务
 *
 * <p>登录流程：{@code wx.login} 得 code → 后端 code2Session 换 openid → 按 openid upsert 会员 → 签发 token。
 *
 * <p>无 AppID 时的联调方案：{@code member.wechat.mock-login=true} 时跳过微信接口，
 * 用 code 派生一个稳定的假 openid（同一 code 永远对应同一会员），
 * 便于本地与自动化测试完整走通登录 → 绑手机 → 查询 → 充值 全流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MpAuthService {

    private static final String WX_SESSION_URL = "https://api.weixin.qq.com/sns/jscode2session";
    private static final String WX_PHONE_URL = "https://api.weixin.qq.com/wxa/business/getuserphonenumber";

    private final MemberMapper memberMapper;
    private final MemberLevelMapper levelMapper;
    private final MemberStatMapper statMapper;
    private final AccountService accountService;
    private final JwtTokenProvider tokenProvider;
    private final SecurityService securityService;
    private final BizNoGenerator noGenerator;
    private final CacheService cacheService;
    private final LoginLogService loginLogService;

    private final RestClient restClient = RestClient.create();

    @Value("${member.wechat.app-id:}")
    private String appId;

    @Value("${member.wechat.app-secret:}")
    private String appSecret;

    @Value("${member.wechat.mock-login:false}")
    private boolean mockLogin;

    /** 是否处于可联调状态（未配置 AppID 或显式开启 Mock） */
    public boolean mockEnabled() {
        return mockLogin || appId == null || appId.isBlank();
    }

    /* ==================================================================
     *  登录
     * ================================================================== */

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> login(String code, String inviteCode) {
        if (code == null || code.isBlank()) {
            throw new BizException(ErrorCode.WX_CODE_INVALID);
        }
        WxSession session = code2Session(code);
        String openid = session.openid();
        String unionid = session.unionid();

        Member member = memberMapper.selectOne(new LambdaQueryWrapper<Member>()
                .eq(Member::getOpenid, openid).last("LIMIT 1"));

        boolean isNew = false;
        if (member == null) {
            member = createGuestMember(openid, unionid, inviteCode);
            isNew = true;
            log.info("[小程序] 新会员注册 memberId={}, memberNo={}, openid={}", member.getId(),
                    member.getMemberNo(), mask(openid));
        } else if (member.isCancelled()) {
            throw new BizException(ErrorCode.MEMBER_CANCELLED);
        }

        LoginUser loginUser = toLoginUser(member);
        loginLogService.mpSuccess(member.getId(), member.getMemberNo(), null, null);
        Map<String, Object> result = new HashMap<>();
        result.put("accessToken", tokenProvider.createAccessToken(loginUser));
        result.put("refreshToken", tokenProvider.createRefreshToken(loginUser));
        result.put("expiresIn", tokenProvider.getAccessTtl());
        result.put("isNewMember", isNew);
        result.put("needBindPhone", !member.hasPhone());

        Map<String, Object> brief = new HashMap<>();
        brief.put("id", member.getId());
        brief.put("memberNo", member.getMemberNo());
        brief.put("nickname", member.getNickname());
        brief.put("avatar", member.getAvatar());
        brief.put("phoneMasked", maskPhone(member.getPhone()));
        brief.put("levelName", levelName(member.getLevelId()));
        result.put("member", brief);
        return result;
    }

    /* ==================================================================
     *  绑定手机号
     * ================================================================== */

    /**
     * 绑定手机号
     *
     * <p>三态判定：
     * <ol>
     *   <li>手机号未被占用 → 直接绑定</li>
     *   <li>已被占用且对方无 openid → 直接绑定到该会员（合并登录态）</li>
     *   <li>已被占用且对方有 openid → 返回冲突，提示走人工合并（UC-02）</li>
     * </ol>
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> bindPhone(Long memberId, String code, String phonePlain) {
        Member member = memberMapper.selectById(memberId);
        if (member == null) {
            throw new BizException(ErrorCode.MEMBER_NOT_FOUND);
        }
        String phone = phonePlain;
        if ((phone == null || phone.isBlank()) && code != null && !code.isBlank()) {
            phone = getPhoneNumber(code);
        }
        if (phone == null || !phone.matches("^1[3-9]\\d{9}$")) {
            throw new BizException(ErrorCode.PHONE_INVALID);
        }

        Member owner = memberMapper.selectOne(new LambdaQueryWrapper<Member>()
                .eq(Member::getPhone, phone).last("LIMIT 1"));

        Map<String, Object> result = new HashMap<>();
        if (owner == null) {
            memberMapper.bindPhone(memberId, phone);
            result.put("bound", true);
            result.put("merged", false);
            result.put("memberId", memberId);
            log.info("[小程序] 绑定手机号成功 memberId={}, phone={}", memberId, maskPhone(phone));
        } else if (owner.getId().equals(memberId)) {
            result.put("bound", true);
            result.put("merged", false);
            result.put("memberId", memberId);
        } else if (owner.getOpenid() == null || owner.getOpenid().isBlank()) {
            // 对方是门店建档的老会员，尚无小程序账号 → 把 openid 迁移过去，登录态切换到老会员
            memberMapper.bindOpenid(owner.getId(), member.getOpenid(), member.getUnionid());
            memberMapper.bindPhone(owner.getId(), phone);
            memberMapper.releasePhone(memberId);
            result.put("bound", true);
            result.put("merged", true);
            result.put("memberId", owner.getId());
            result.put("tip", "已关联到既有会员卡，资产与权益已同步");
            log.info("[小程序] openid 迁移至老会员 oldMemberId={} <- guestMemberId={}", owner.getId(), memberId);
            Member merged = memberMapper.selectById(owner.getId());
            LoginUser loginUser = toLoginUser(merged);
            result.put("accessToken", tokenProvider.createAccessToken(loginUser));
            result.put("refreshToken", tokenProvider.createRefreshToken(loginUser));
        } else {
            result.put("bound", false);
            result.put("merged", false);
            result.put("conflict", true);
            result.put("tip", "该手机号已绑定其他微信账号，请联系门店处理合并");
            log.warn("[小程序] 手机号冲突 memberId={}, ownerId={}", memberId, owner.getId());
        }
        return result;
    }

    /** 刷新令牌（会员端） */
    public Map<String, Object> refresh(String refreshToken) {
        var claims = tokenProvider.parse(refreshToken);
        if (!tokenProvider.isRefreshToken(claims)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "令牌类型不正确");
        }
        Long memberId = tokenProvider.getUserId(claims);
        Member member = memberMapper.selectById(memberId);
        if (member == null || member.isCancelled()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "会员不存在或已注销");
        }
        LoginUser loginUser = toLoginUser(member);
        Map<String, Object> result = new HashMap<>();
        result.put("accessToken", tokenProvider.createAccessToken(loginUser));
        result.put("refreshToken", tokenProvider.createRefreshToken(loginUser));
        result.put("expiresIn", tokenProvider.getAccessTtl());
        return result;
    }

    /* ==================================================================
     *  资料
     * ================================================================== */

    @Transactional(rollbackFor = Exception.class)
    public void updateProfile(Long memberId, Map<String, Object> body) {
        Member member = memberMapper.selectById(memberId);
        if (member == null) {
            throw new BizException(ErrorCode.MEMBER_NOT_FOUND);
        }
        if (body.get("nickname") != null) {
            member.setNickname(String.valueOf(body.get("nickname")));
        }
        if (body.get("avatar") != null) {
            member.setAvatar(String.valueOf(body.get("avatar")));
        }
        if (body.get("realName") != null) {
            member.setRealName(String.valueOf(body.get("realName")));
        }
        if (body.get("gender") != null) {
            member.setGender(Integer.valueOf(String.valueOf(body.get("gender"))));
        }
        if (body.get("birthday") != null && !String.valueOf(body.get("birthday")).isBlank()) {
            try {
                member.setBirthday(java.time.LocalDate.parse(String.valueOf(body.get("birthday"))));
            } catch (Exception e) {
                throw new BizException(ErrorCode.PARAM_INVALID, "生日格式应为 yyyy-MM-dd");
            }
        }
        memberMapper.updateById(member);
        log.info("[小程序] 更新资料 memberId={}", memberId);
    }

    public Map<String, Object> profile(Long memberId) {
        Member member = memberMapper.selectById(memberId);
        if (member == null) {
            throw new BizException(ErrorCode.MEMBER_NOT_FOUND);
        }
        Map<String, Object> m = new HashMap<>();
        m.put("id", member.getId());
        m.put("memberNo", member.getMemberNo());
        m.put("nickname", member.getNickname());
        m.put("avatar", member.getAvatar());
        m.put("realName", member.getRealName());
        m.put("gender", member.getGender());
        m.put("birthday", member.getBirthday());
        m.put("phoneMasked", maskPhone(member.getPhone()));
        m.put("hasPhone", member.hasPhone());
        m.put("levelId", member.getLevelId());
        m.put("levelName", levelName(member.getLevelId()));
        m.put("status", member.getStatus());
        m.put("storeId", member.getStoreId());
        m.put("createdAt", member.getCreatedAt());
        return m;
    }

    /* ==================================================================
     *  微信接口封装
     * ================================================================== */

    private record WxSession(String openid, String unionid, String sessionKey) {
    }

    @SuppressWarnings("unchecked")
    private WxSession code2Session(String code) {
        if (mockEnabled()) {
            // 联调模式：code 派生稳定 openid（同一 code 永远同一会员）
            String openid = "mock_openid_" + Integer.toHexString(code.hashCode());
            log.info("[小程序] Mock 登录模式，code={} -> openid={}", code, mask(openid));
            return new WxSession(openid, null, "mock_session_key");
        }
        try {
            Map<String, Object> resp = restClient.get()
                    .uri(WX_SESSION_URL + "?appid={appid}&secret={secret}&js_code={code}&grant_type=authorization_code",
                            appId, appSecret, code)
                    .retrieve()
                    .body(Map.class);
            if (resp == null) {
                throw new BizException(ErrorCode.WX_REQUEST_TIMEOUT);
            }
            Object errcode = resp.get("errcode");
            if (errcode != null && !"0".equals(String.valueOf(errcode))) {
                log.warn("[小程序] code2Session 失败: {}", resp);
                throw new BizException(ErrorCode.WX_CODE_INVALID, String.valueOf(resp.get("errmsg")));
            }
            String openid = String.valueOf(resp.get("openid"));
            Object unionid = resp.get("unionid");
            String sessionKey = String.valueOf(resp.get("session_key"));
            if (openid == null || openid.isBlank() || "null".equals(openid)) {
                throw new BizException(ErrorCode.WX_CODE_INVALID);
            }
            // session_key 只进缓存，不落库也不返回前端
            cacheService.set("wx:session:" + openid, sessionKey, 7, TimeUnit.DAYS);
            return new WxSession(openid, unionid == null ? null : String.valueOf(unionid), sessionKey);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[小程序] 请求微信 code2Session 异常", e);
            throw new BizException(ErrorCode.WX_REQUEST_TIMEOUT);
        }
    }

    @SuppressWarnings("unchecked")
    private String getPhoneNumber(String code) {
        if (mockEnabled()) {
            // 联调模式：用 code 后 8 位拼一个合法的测试手机号
            String digits = String.format("%08d", Math.abs(code.hashCode()) % 100000000);
            return "138" + digits;
        }
        try {
            Map<String, Object> token = getAccessToken();
            String accessToken = String.valueOf(token.get("access_token"));
            Map<String, Object> resp = restClient.post()
                    .uri(WX_PHONE_URL + "?access_token={token}", accessToken)
                    .body(Map.of("code", code))
                    .retrieve()
                    .body(Map.class);
            if (resp == null || !"0".equals(String.valueOf(resp.get("errcode")))) {
                log.warn("[小程序] getPhoneNumber 失败: {}", resp);
                throw new BizException(ErrorCode.WX_PHONE_DECRYPT_FAIL);
            }
            Map<String, Object> info = (Map<String, Object>) resp.get("phone_info");
            if (info == null) {
                throw new BizException(ErrorCode.WX_PHONE_DECRYPT_FAIL);
            }
            return String.valueOf(info.get("phoneNumber"));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[小程序] 获取手机号异常", e);
            throw new BizException(ErrorCode.WX_PHONE_DECRYPT_FAIL);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getAccessToken() {
        String cached = cacheService.getString("wx:access_token");
        if (cached != null && !cached.isBlank()) {
            return Map.of("access_token", cached);
        }
        Map<String, Object> resp = restClient.get()
                .uri("https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid={appid}&secret={secret}",
                        appId, appSecret)
                .retrieve()
                .body(Map.class);
        if (resp == null || resp.get("access_token") == null) {
            throw new BizException(ErrorCode.WX_REQUEST_TIMEOUT, "获取微信 access_token 失败");
        }
        String token = String.valueOf(resp.get("access_token"));
        long expiresIn = Long.parseLong(String.valueOf(resp.getOrDefault("expires_in", 7200)));
        cacheService.set("wx:access_token", token, Math.max(expiresIn - 300, 60), TimeUnit.SECONDS);
        return resp;
    }

    /* ==================================================================
     *  内部
     * ================================================================== */

    /** 微信游客会员建档（无手机号） */
    private Member createGuestMember(String openid, String unionid, String inviteCode) {
        Member member = new Member();
        member.setMemberNo(noGenerator.generateWithRetry(noGenerator::memberNo,
                no -> memberMapper.selectCount(new LambdaQueryWrapper<Member>().eq(Member::getMemberNo, no)) > 0));
        member.setOpenid(openid);
        member.setUnionid(unionid);
        member.setNickname("微信用户" + member.getMemberNo().substring(member.getMemberNo().length() - 4));
        member.setGender(0);
        member.setSource(inviteCode != null && !inviteCode.isBlank() ? 4 : 2);
        member.setStatus(1);
        member.setStoreId(1L);
        member.setDeleted(0);
        member.setTenantId(0L);
        // 推荐人解析（邀请码 = 推荐人会员号）
        if (inviteCode != null && !inviteCode.isBlank()) {
            Member referrer = memberMapper.selectOne(new LambdaQueryWrapper<Member>()
                    .eq(Member::getMemberNo, inviteCode.trim()).last("LIMIT 1"));
            if (referrer != null && !referrer.getId().equals(member.getId())) {
                member.setReferrerId(referrer.getId());
            }
        }
        MemberLevel level = levelMapper.selectDefaultLevel();
        if (level != null) {
            member.setLevelId(level.getId());
        }
        memberMapper.insert(member);

        accountService.initAccount(member.getId());

        MemberStat stat = new MemberStat();
        stat.setMemberId(member.getId());
        stat.setPayAmount30d(BigDecimal.ZERO);
        stat.setPayAmount90d(BigDecimal.ZERO);
        stat.setPayAmount12m(BigDecimal.ZERO);
        stat.setOrderCount12m(0);
        stat.setVisitDays30d(0);
        stat.setAvgOrderAmount(BigDecimal.ZERO);
        statMapper.insert(stat);
        return member;
    }

    private LoginUser toLoginUser(Member member) {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(member.getId());
        loginUser.setUsername(member.getMemberNo());
        loginUser.setRealName(member.getNickname());
        loginUser.setUserType("MP");
        loginUser.setStoreId(member.getStoreId());
        loginUser.setDataScope(3);
        loginUser.setStatus(member.getStatus() == null || member.getStatus() == 1 ? 1 : 0);
        loginUser.setRoles(Set.of("MEMBER"));
        loginUser.setPerms(Set.of("mp:member"));
        return loginUser;
    }

    private String levelName(Long levelId) {
        if (levelId == null) {
            return null;
        }
        MemberLevel level = levelMapper.selectById(levelId);
        return level == null ? null : level.getLevelName();
    }

    private static String mask(String openid) {
        return openid == null || openid.length() <= 12 ? openid
                : openid.substring(0, 8) + "****" + openid.substring(openid.length() - 4);
    }

    static String maskPhone(String phone) {
        return phone == null || phone.length() < 7 ? phone
                : phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

        /** 供管理端查看联调模式状态 */
    public Map<String, Object> configStatus() {
        Map<String, Object> m = new HashMap<>();
        m.put("appIdConfigured", appId != null && !appId.isBlank());
        m.put("mockLogin", mockEnabled());
        m.put("tip", mockEnabled()
                ? "当前为联调模式：wx.login 不会调用微信接口，使用 code 派生稳定 openid，可完整跑通登录/绑定/充值流程"
                : "已配置真实 AppID，将调用微信接口");
        return m;
    }

    /** 供测试：直接按 openid 查会员 */
    public Member findByOpenid(String openid) {
        return memberMapper.selectOne(new LambdaQueryWrapper<Member>()
                .eq(Member::getOpenid, openid).last("LIMIT 1"));
    }

    /** 供内部调试：模拟一个手机号 */
    public String mockPhone(String seed) {
        String digits = String.format("%08d", Math.abs(seed.hashCode()) % 100000000);
        return "138" + digits;
    }

}
