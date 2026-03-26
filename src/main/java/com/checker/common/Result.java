package com.checker.common;

import lombok.Data;

import java.io.Serializable;

@Data
public class Result<T> implements Serializable {
    private Integer code;    // 状态码 (例如: 200 成功, 500 失败)
    private String msg;      // 提示消息
    private T data;          // 业务数据
    private Long timestamp;  // 响应时间戳

    public Result() {
        this.timestamp = System.currentTimeMillis();
    }

    // 成功静态方法
    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMsg("操作成功");
        result.setData(data);
        return result;
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    // 失败静态方法
    public static <T> Result<T> error(Integer code, String msg) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMsg(msg);
        return result;
    }

    public static <T> Result<T> error(String msg) {
        return error(500, msg);
    }
}
