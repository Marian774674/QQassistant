package com.czx.qqmessageassistantjava.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class MessageController {

    @CrossOrigin(origins = "http://localhost:5173")
    @PostMapping("/message")
    public String message(@RequestBody String query) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        payload.put("user", "user_001");
        // Deleted:payload.put("input", "");
        payload.put("inputs", new LinkedHashMap<String, Object>());
        payload.put("response_mode", "blocking");

        String body = new ObjectMapper().writeValueAsString(payload);

        HttpResponse<String> response = Unirest.post("http://127.0.0.1/v1/chat-messages")
                .header("Authorization", "Bearer app-uQEbb199KCC3Cj5kPdGCNLKa")
                .header("Content-Type", "application/json")
                .body(body)
                .asString();
        return response.getBody();
    }
}