package com.czx.qqmessageassistantjava.vo;

import lombok.Data;

/** 一条能点回原文的证据；id 带类型前缀，四张表自增不会撞车 */
@Data
public class EvidenceVO {
    private String type;
    private String id;
    private String label;
    private String content;
}
