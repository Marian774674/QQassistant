package com.czx.qqmessageassistantjava.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.entity.Contact;
import com.czx.qqmessageassistantjava.mapper.ContactMapper;
import com.czx.qqmessageassistantjava.service.ContactService;
import com.czx.qqmessageassistantjava.vo.ContactVO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ContactServiceImpl extends ServiceImpl<ContactMapper, Contact> implements ContactService {

    private static final String AUTO = "AUTO";
    private static final String USER = "USER";
    private static final int ALIAS_LIMIT = 64;
    private static final int RELATION_LIMIT = 64;

    @Override
    public List<ContactVO> list(Long senderId) {
        LambdaQueryWrapper<Contact> query = new LambdaQueryWrapper<Contact>()
                .orderByAsc(Contact::getAlias)
                .orderByAsc(Contact::getSenderId);
        if (senderId != null) {
            query.eq(Contact::getSenderId, senderId);
        }
        return list(query).stream().map(ContactServiceImpl::toVO).toList();
    }

    @Override
    public Contact upsertAuto(Long senderId, String alias, String relation) {
        requireAlias(alias);
        Contact existing = findOne(senderId, alias);
        if (existing == null) {
            return saveNew(senderId, alias, relation, AUTO);
        }
        // 用户认过的行不动：夜间任务猜错了不该把用户的手工更正改回来
        if (!USER.equals(existing.getSource())) {
            String next = truncate(relation, RELATION_LIMIT);
            if (!next.isBlank() && !next.equals(existing.getRelation())) {
                existing.setRelation(next);
                touch(existing);
                updateById(existing);
            }
        }
        return existing;
    }

    @Override
    public Contact addUser(Long senderId, String alias, String relation) {
        requireAlias(alias);
        Contact existing = findOne(senderId, alias);
        if (existing == null) {
            return saveNew(senderId, alias, relation, USER);
        }
        String next = truncate(relation, RELATION_LIMIT);
        if (!next.isBlank()) {
            existing.setRelation(next);
        }
        existing.setSource(USER);
        touch(existing);
        updateById(existing);
        return existing;
    }

    @Override
    public List<Contact> findByAlias(String alias) {
        String trimmed = alias == null ? "" : alias.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        return list(new LambdaQueryWrapper<Contact>().eq(Contact::getAlias, trimmed));
    }

    @Override
    public void delete(Long id) {
        if (id == null) {
            throw new BusinessException("缺少联系人 id");
        }
        removeById(id);
    }

    private Contact findOne(Long senderId, String alias) {
        if (senderId == null) {
            throw new BusinessException("缺少 QQ 号");
        }
        return getOne(new LambdaQueryWrapper<Contact>()
                .eq(Contact::getSenderId, senderId)
                .eq(Contact::getAlias, alias.trim()), false);
    }

    private Contact saveNew(Long senderId, String alias, String relation, String source) {
        Contact contact = new Contact();
        contact.setSenderId(senderId);
        contact.setAlias(alias.trim());
        contact.setRelation(truncate(relation, RELATION_LIMIT));
        contact.setImportance(0);
        contact.setSource(source);
        // 与 message_log / memory_note 同一个钟：那台 MySQL 跑在 UTC，用库默认值会差 8 小时
        LocalDateTime now = LocalDateTime.now();
        contact.setCreatedAt(now);
        contact.setUpdatedAt(now);
        save(contact);
        return contact;
    }

    /**
     * 更新前把 updated_at 显式写成现在。
     *
     * <p>读出来的实体带着旧的 updated_at，MyBatis-Plus 会把它原样放进 SET 子句，
     * 库上的 {@code ON UPDATE CURRENT_TIMESTAMP} 根本不生效——不写这一行，时间就永远是第一次插入的时间。
     */
    private static void touch(Contact contact) {
        contact.setUpdatedAt(LocalDateTime.now());
    }

    private void requireAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            throw new BusinessException("称呼不能为空");
        }
        if (alias.trim().length() > ALIAS_LIMIT) {
            throw new BusinessException("称呼太长");
        }
    }

    private static String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= limit ? trimmed : trimmed.substring(0, limit);
    }

    private static ContactVO toVO(Contact contact) {
        ContactVO vo = new ContactVO();
        vo.setId(contact.getId());
        vo.setSenderId(contact.getSenderId());
        vo.setAlias(contact.getAlias());
        vo.setRelation(contact.getRelation());
        vo.setImportance(contact.getImportance());
        vo.setNote(contact.getNote());
        vo.setSource(contact.getSource());
        vo.setUpdatedAt(contact.getUpdatedAt());
        return vo;
    }
}
