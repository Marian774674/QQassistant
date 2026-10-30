package com.czx.qqmessageassistantjava.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.czx.qqmessageassistantjava.entity.Contact;
import com.czx.qqmessageassistantjava.vo.ContactVO;

import java.util.List;

public interface ContactService extends IService<Contact> {

    /** senderId 为空=全部；按称呼升序 */
    List<ContactVO> list(Long senderId);

    /** 夜间任务认出来的人：已存在且是 USER 行时一个字都不改 */
    Contact upsertAuto(Long senderId, String alias, String relation);

    /** 用户认过：存在则更新关系并置 source=USER */
    Contact addUser(Long senderId, String alias, String relation);

    /** 认人用：精确匹配，可能返回多个 sender_id（=冲突） */
    List<Contact> findByAlias(String alias);

    void delete(Long id);
}
