package com.czx.qqmessageassistantjava.vo;

import lombok.Data;

import java.util.List;

@Data
public class ClarifyStateVO {
    private String alias;
    private List<ClarifyCandidateVO> candidates;
}
