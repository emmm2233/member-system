package com.company.member.security;

import com.company.member.common.constant.OperatorContext;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 登录用户（管理端与小程序端共用）
 *
 * <p><b>为什么要写 @JsonIgnore（切 Redis 实测出来的）</b>：这个对象会被缓存
 * （{@code loginuser:ADMIN:{id}}，5 分钟），而 Redis 实现走 JSON 往返。
 * {@link #getAuthorities()} 返回的是 Spring Security 的抽象类型
 * {@code GrantedAuthority}，Jackson 能写出去却读不回来：
 *
 * <pre>
 * Cannot construct instance of `org.springframework.security.core.GrantedAuthority`
 * (no Creators, like default constructor, exist)
 * </pre>
 *
 * 后果是登录态缓存<b>永不命中</b>：每个请求都要重新查库 + 查权限，
 * 而且每个请求打一条 WARN（实测一次全量自检刷出 349 条）。
 * {@code perms} 才是真相来源，{@code authorities} 只是它的派生视图 —— 缓存里本就不该存派生值。
 * {@code enabled / accountNonLocked} 等 {@code UserDetails} 派生属性同理。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LoginUser implements UserDetails {

    private Long userId;
    private String username;
    private String password;
    private String realName;
    /** ADMIN / MP */
    private String userType;
    private Long storeId;
    private Integer dataScope;
    private String scopeStoreIds;
    /** 角色编码集合 */
    private Set<String> roles;
    /** 权限码集合（鉴权的唯一真相来源） */
    private Set<String> perms;
    private Integer status;

    /** 派生视图：由 perms 生成，不参与序列化（否则 JSON 缓存读不回来） */
    @Override
    @JsonIgnore
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if (perms == null || perms.isEmpty()) {
            return List.of();
        }
        return perms.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toSet());
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    /** 由 status 派生，不参与序列化 */
    @Override
    @JsonIgnore
    public boolean isAccountNonLocked() {
        return status == null || status == 1;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /** 由 status 派生，不参与序列化 */
    @Override
    @JsonIgnore
    public boolean isEnabled() {
        return status == null || status == 1;
    }

    /** 由 userType 派生，不参与序列化 */
    @JsonIgnore
    public boolean isMemberSide() {
        return "MP".equals(userType);
    }

    /** 转换为业务上下文操作人（派生，不参与序列化） */
    @JsonIgnore
    public OperatorContext.Operator toOperator() {
        return new OperatorContext.Operator(userId, username, realName, userType, storeId, dataScope, scopeStoreIds);
    }
}
