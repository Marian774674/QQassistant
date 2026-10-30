package com.czx.qqmessageassistantjava.dto;

import lombok.Data;

/** 用户对「这是谁」的回答；前端原样带回上一轮的问题 */
@Data
public class ClarifyAnswer {
    private String alias;
    private Long senderId;
    /** 用户当时怎么说的，记进 contact.note 的备选；当前只写 relation 之外的人工备注 */
    private String from;
}
