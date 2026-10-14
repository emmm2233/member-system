package com.company.member.common.constant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前操作人上下文（由安全过滤器写入，业务层用于审计与数据权限）
 */
public final class OperatorContext {

    private static final ThreadLocal<Operator> HOLDER = new ThreadLocal<>();

    private OperatorContext() {
    }

    public static void set(Operator operator) {
        HOLDER.set(operator);
    }

    public static Operator get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static Long userId() {
        Operator op = HOLDER.get();
        return op == null ? null : op.getUserId();
    }

    public static String username() {
        Operator op = HOLDER.get();
        return op == null ? null : op.getUsername();
    }

    public static Long storeId() {
        Operator op = HOLDER.get();
        return op == null ? null : op.getStoreId();
    }

    public static Integer dataScope() {
        Operator op = HOLDER.get();
        return op == null ? null : op.getDataScope();
    }

    /** 是否为会员端（小程序）登录 */
    public static boolean isMemberSide() {
        Operator op = HOLDER.get();
        return op != null && "MP".equals(op.getUserType());
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Operator {
        /** 管理端为 sys_user.id；会员端为 member.id */
        private Long userId;
        private String username;
        private String realName;
        /** 端类型：ADMIN / MP */
        private String userType;
        private Long storeId;
        /** 1全部 2本店 3本人 4自定义 */
        private Integer dataScope;
        /** 自定义数据范围的门店 id（逗号分隔） */
        private String scopeStoreIds;
    }
}
