package com.company.member.common.exception;

import com.company.member.common.result.ErrorCode;
import lombok.Getter;

/**
 * 业务异常：所有可预期的业务失败统一抛此异常，由 GlobalExceptionHandler 翻译为响应体
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMsg());
        this.code = errorCode.getCode();
    }

    public BizException(ErrorCode errorCode, String msg) {
        super(msg);
        this.code = errorCode.getCode();
    }

    public BizException(ErrorCode errorCode, String msg, Throwable cause) {
        super(msg, cause);
        this.code = errorCode.getCode();
    }

    public static BizException of(ErrorCode errorCode) {
        return new BizException(errorCode);
    }

    public static BizException of(ErrorCode errorCode, String msg) {
        return new BizException(errorCode, msg);
    }

    /** 条件成立则抛出 */
    public static void throwIf(boolean condition, ErrorCode errorCode) {
        if (condition) {
            throw new BizException(errorCode);
        }
    }

    public static void throwIf(boolean condition, ErrorCode errorCode, String msg) {
        if (condition) {
            throw new BizException(errorCode, msg);
        }
    }
}
