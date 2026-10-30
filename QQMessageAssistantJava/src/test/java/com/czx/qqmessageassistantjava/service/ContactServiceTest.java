package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.entity.Contact;
import com.czx.qqmessageassistantjava.vo.ContactVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 人物表的规矩只有一条：用户认过的人（{@code source=USER}）不能被夜间任务改回去。
 * 这些行为只有在真库上才验得出来（唯一键、source 语义、按别名查多条）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ContactServiceTest {

    @Autowired
    private ContactService contactService;

    private static final long SENDER = 9_920_001L;

    @Test
    void timestampsAreWrittenByJavaInLocalTime() {
        Contact created = contactService.addUser(SENDER + 4, "阿黄-" + System.nanoTime(), "室友");

        // 那台 MySQL 跑在 UTC：靠库里的 CURRENT_TIMESTAMP 会和 Java 的本地时间差 8 小时
        assertThat(created.getCreatedAt()).isNotNull();
        assertThat(Duration.between(created.getCreatedAt(), LocalDateTime.now()).abs())
                .isLessThan(Duration.ofMinutes(2));
    }

    @Test
    void upsertAuto_movesUpdatedAtForward() {
        // AUTO 行才会被夜间任务更新；USER 行按设计不动
        Contact created = contactService.upsertAuto(SENDER + 6, "小周-" + System.nanoTime(), "同学");
        LocalDateTime ancient = LocalDateTime.of(2000, 1, 1, 0, 0);
        Contact stale = new Contact();
        stale.setId(created.getId());
        stale.setUpdatedAt(ancient);
        contactService.updateById(stale);

        contactService.upsertAuto(SENDER + 6, created.getAlias(), "室友");

        // MyBatis-Plus 会把读出来的旧 updated_at 原样写回，压掉库的 ON UPDATE
        assertThat(contactService.getById(created.getId()).getUpdatedAt())
                .isAfter(ancient.plusYears(1));
    }

    @Test
    void upsertAuto_neverOverwritesAUserRow() {
        contactService.addUser(SENDER, "小明", "同学");

        contactService.upsertAuto(SENDER, "小明", "隔壁班的");

        List<Contact> found = contactService.findByAlias("小明").stream()
                .filter(c -> SENDER == c.getSenderId())
                .toList();
        assertThat(found).singleElement().satisfies(c -> {
            assertThat(c.getSource()).isEqualTo("USER");
            assertThat(c.getRelation()).isEqualTo("同学");
        });
    }

    @Test
    void upsertAuto_isIdempotentForTheSameAlias() {
        contactService.upsertAuto(SENDER + 1, "小红", "同学");
        contactService.upsertAuto(SENDER + 1, "小红", "同桌");

        List<ContactVO> found = contactService.list(SENDER + 1);
        assertThat(found).singleElement()
                .satisfies(c -> assertThat(c.getRelation()).isEqualTo("同桌"));
    }

    @Test
    void findByAlias_returnsEverySenderUsingThatName() {
        contactService.addUser(SENDER + 2, "小明", "室友");
        contactService.addUser(SENDER + 3, "小明", "同学");

        assertThat(contactService.findByAlias("小明"))
                .extracting(Contact::getSenderId)
                .contains(SENDER + 2, SENDER + 3);
    }

    @Test
    void addUser_promotesAnAutoRowToUser() {
        contactService.upsertAuto(SENDER + 4, "阿强", "");
        contactService.addUser(SENDER + 4, "阿强", "同事");

        assertThat(contactService.list(SENDER + 4)).singleElement().satisfies(c -> {
            assertThat(c.getSource()).isEqualTo("USER");
            assertThat(c.getRelation()).isEqualTo("同事");
        });
    }

    @Test
    void addUser_rejectsAnEmptyAlias() {
        assertThatThrownBy(() -> contactService.addUser(SENDER + 5, "  ", ""))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("称呼");
    }
}
