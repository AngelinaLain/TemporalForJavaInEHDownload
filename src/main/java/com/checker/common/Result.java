package com.checker.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一 API 响应包装类
 *
 * @param <T> 业务数据类型
 */
@Data
public class Result<T> implements Serializable {
    private Integer code;    // 状态码 (例如: 200 成功, 500 失败)
    private String msg;      // 提示消息
    private T data;          // 业务数据
    private Long timestamp;  // 响应时间戳

    /**
     * 默认构造，自动设置当前时间戳
     */
    public Result() {
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 构建成功响应（携带数据）
     *
     * @param data 业务数据
     * @param <T>  数据类型
     * @return 包含数据的成功 Result
     */
    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMsg("操作成功");
        result.setData(data);
        return result;
    }

    /**
     * 构建成功响应（无数据）
     *
     * @param <T> 数据类型
     * @return 不含数据的成功 Result
     */
    public static <T> Result<T> success() {
        return success(null);
    }

    /**
     * 构建失败响应（自定义状态码与消息）
     *
     * @param code 错误状态码
     * @param msg  错误消息
     * @param <T>  数据类型
     * @return 失败 Result
     */
    public static <T> Result<T> error(Integer code, String msg) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMsg(msg);
        return result;
    }

    /**
     * 构建失败响应（默认 500 状态码）
     *
     * @param msg 错误消息
     * @param <T> 数据类型
     * @return 失败 Result
     */
    public static <T> Result<T> error(String msg) {
        return error(500, msg);
    }
}
