package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.dto.ContactRequest;
import com.czx.qqmessageassistantjava.entity.Contact;
import com.czx.qqmessageassistantjava.service.ContactService;
import com.czx.qqmessageassistantjava.vo.ContactVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "认识的人")
@RestController
@RequestMapping("/api/contacts")
public class ContactController {

    private final ContactService contactService;

    public ContactController(ContactService contactService) {
        this.contactService = contactService;
    }

    @Operation(summary = "人物列表；senderId 可选")
    @GetMapping
    public Result<List<ContactVO>> list(@RequestParam(required = false) Long senderId) {
        return Result.ok(contactService.list(senderId));
    }

    @Operation(summary = "手工认人（source=USER，夜间任务不再覆盖）")
    @PostMapping
    public Result<Contact> create(@RequestBody ContactRequest request) {
        if (request == null) {
            throw new BusinessException("请求不能为空");
        }
        return Result.ok(contactService.addUser(
                request.getSenderId(), request.getAlias(), request.getRelation()));
    }

    @Operation(summary = "删除一个称呼")
    @DeleteMapping("/{id}")
    public Result<Integer> delete(@PathVariable Long id) {
        contactService.delete(id);
        return Result.ok(1);
    }
}
