package com.company.member.common.util;

/**
 * 敏感信息脱敏工具
 */
public final class MaskUtil {

    private MaskUtil() {
    }

    /** 手机号：138****8888 */
    public static String phone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /** 姓名：张*三 / 张** */
    public static String name(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        if (name.length() == 1) {
            return name;
        }
        if (name.length() == 2) {
            return name.charAt(0) + "*";
        }
        return name.charAt(0) + "*".repeat(name.length() - 2) + name.charAt(name.length() - 1);
    }

    /** 银行卡/券码等长串：保留前 4 后 4 */
    public static String code(String code) {
        if (code == null || code.length() <= 8) {
            return code;
        }
        return code.substring(0, 4) + "****" + code.substring(code.length() - 4);
    }

    /** openid：保留前 6 后 4 */
    public static String openid(String openid) {
        if (openid == null || openid.length() <= 10) {
            return openid;
        }
        return openid.substring(0, 6) + "****" + openid.substring(openid.length() - 4);
    }
}
