package com.czx.qqmessageassistantjava.common;

import lombok.Data;

@Data
public class Result<T> {

    private int code;
    private String message;
    private T data;

    private Result(int code,String message,T data){
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> ok(){
        return new Result<>(0,"ok",null);
    }

    public static <T> Result<T> ok(T data){
        return new Result<>(0,"ok",data);
    }

    /** 需要把「人话确认语」带出去时用（例如 Agent 写操作的 "改好了：…"）。 */
    public static <T> Result<T> ok(String message,T data){
        return new Result<>(0,message,data);
    }

    public static <T> Result<T> fail(int code,String message){
        return new Result<>(code,message,null);
    }
}
