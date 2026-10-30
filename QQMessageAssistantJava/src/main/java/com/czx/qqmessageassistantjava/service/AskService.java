package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.dto.AskRequest;
import com.czx.qqmessageassistantjava.vo.AskResponse;

public interface AskService {

    AskResponse ask(AskRequest request);
}
