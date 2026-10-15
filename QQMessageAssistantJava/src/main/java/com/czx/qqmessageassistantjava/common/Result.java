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

    public static <T> Result<T> fail(int code,String message){
        return new Result<>(code,message,null);
    }
}
