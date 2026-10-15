package com.czx.qqmessageassistantjava.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException e){
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(400, e.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<Result<Void>> handleParam(Exception e){
        String message = "参数错误";
        if (e instanceof MethodArgumentNotValidException valid
        && valid.getBindingResult().getFieldError()!=null){
            var error = valid.getBindingResult().getFieldError();
            message += error.getField() + " " + error.getDefaultMessage();
        } else if (e instanceof MissingServletRequestParameterException missing) {
            message += missing.getParameterName() + " 不能为空";
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(400, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleSystem(Exception e){
        log.error("系统错误", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(500, "系统错误"));
    }
}
