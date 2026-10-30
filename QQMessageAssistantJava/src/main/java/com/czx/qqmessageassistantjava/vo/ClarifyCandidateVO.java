package com.czx.qqmessageassistantjava.vo;

import lombok.Data;

@Data
public class ClarifyCandidateVO {
    private Long senderId;
    private String alias;
    private String relation;
    /** 让用户认得出的线索，例如「三班群 · 2026-10-28」 */
    private String hint;
}
