package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.entity.ChatAlias;
import com.czx.qqmessageassistantjava.mapper.ChatAliasMapper;
import com.czx.qqmessageassistantjava.mapper.DraftMapper;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import com.czx.qqmessageassistantjava.service.impl.ChatAliasServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatAliasServiceImplTest {

    @Mock
    ChatAliasMapper chatAliasMapper;

    @Mock
    DraftMapper draftMapper;

    @Mock
    MessageLogMapper messageLogMapper;

    private ChatAliasServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ChatAliasServiceImpl(chatAliasMapper, draftMapper, messageLogMapper);
    }

    @Test
    void aliases_splitsTrimsAndDropsBlanks() {
        when(chatAliasMapper.find(1L, "GROUP"))
                .thenReturn(row(1L, "GROUP", " 小明, ,阿明,小明 "));

        assertThat(service.aliases(1L, "GROUP")).containsExactly("小明", "阿明");
    }

    @Test
    void aliases_returnsEmptyWhenThereIsNoRow() {
        when(chatAliasMapper.find(1L, "PRIVATE")).thenReturn(null);

        assertThat(service.aliases(1L, "PRIVATE")).isEmpty();
    }

    @Test
    void displayName_usesTheFirstAlias() {
        when(chatAliasMapper.find(1L, "GROUP")).thenReturn(row(1L, "GROUP", "小明,阿明"));
        when(chatAliasMapper.find(2L, "GROUP")).thenReturn(row(2L, "GROUP", ""));

        assertThat(service.displayName(1L, "GROUP")).isEqualTo("小明");
        assertThat(service.displayName(2L, "GROUP")).isNull();
    }

    @Test
    void saveAliases_dedupesAndDropsBlankSegments() {
        when(chatAliasMapper.find(1L, "GROUP")).thenReturn(null);

        service.saveAliases(1L, "GROUP", "a, b ,a,,c");

        ArgumentCaptor<ChatAlias> captor = ArgumentCaptor.forClass(ChatAlias.class);
        verify(chatAliasMapper).insert(captor.capture());
        assertThat(captor.getValue().getAliases()).isEqualTo("a,b,c");
    }

    @Test
    void saveAliases_truncatesTo255() {
        when(chatAliasMapper.find(1L, "GROUP")).thenReturn(null);
        String longName = "x".repeat(300);

        service.saveAliases(1L, "GROUP", longName);

        ArgumentCaptor<ChatAlias> captor = ArgumentCaptor.forClass(ChatAlias.class);
        verify(chatAliasMapper).insert(captor.capture());
        assertThat(captor.getValue().getAliases()).hasSize(255);
    }

    @Test
    void saveAliases_updatesTheExistingRow() {
        ChatAlias existing = row(5L, "GROUP", "旧名字");
        existing.setId(5L);
        when(chatAliasMapper.find(1L, "GROUP")).thenReturn(existing);

        service.saveAliases(1L, "GROUP", "新名字,另一个");

        ArgumentCaptor<ChatAlias> captor = ArgumentCaptor.forClass(ChatAlias.class);
        verify(chatAliasMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(5L);
        assertThat(captor.getValue().getAliases()).isEqualTo("新名字,另一个");
        assertThat(captor.getValue().getUpdatedAt()).isNotNull();
        verify(chatAliasMapper, never()).insert(any(ChatAlias.class));
    }

    @Test
    void saveAliases_allowsAnEmptyStringToMeanRejected() {
        when(chatAliasMapper.find(1L, "GROUP")).thenReturn(null);

        service.saveAliases(1L, "GROUP", "  ");

        ArgumentCaptor<ChatAlias> captor = ArgumentCaptor.forClass(ChatAlias.class);
        verify(chatAliasMapper).insert(captor.capture());
        assertThat(captor.getValue().getAliases()).isEmpty();
    }

    @Test
    void hasAliasRow_distinguishesKindsWithSameNumber() {
        when(chatAliasMapper.find(123L, "GROUP")).thenReturn(row(123L, "GROUP", "群名"));
        when(chatAliasMapper.find(123L, "PRIVATE")).thenReturn(null);

        assertThat(service.hasAliasRow(123L, "GROUP")).isTrue();
        assertThat(service.hasAliasRow(123L, "PRIVATE")).isFalse();
    }

    @Test
    void displayNames_keysByKindAndChatId() {
        when(chatAliasMapper.findAll()).thenReturn(List.of(
                row(1L, "GROUP", "三班群,班群"),
                row(2L, "PRIVATE", "阿黄")));

        Map<String, String> names = service.displayNames();

        assertThat(names).containsEntry("GROUP:1", "三班群")
                .containsEntry("PRIVATE:2", "阿黄")
                .hasSize(2);
    }

    @Test
    void listAllAliases_returnsEveryRowForTheMemoryPage() {
        when(chatAliasMapper.findAll()).thenReturn(List.of(
                row(1L, "GROUP", "三班群,班群"),
                row(2L, "PRIVATE", "阿黄")));

        List<ChatAlias> all = service.listAllAliases();

        assertThat(all).hasSize(2);
        assertThat(all.get(0).getAliases()).isEqualTo("三班群,班群");
    }

    @Test
    void deleteAliases_removesOnlyThatKind() {
        // 群号可能等于某人的 QQ：删群名不能顺手把私聊名字也删了
        service.deleteAliases(123L, "GROUP");

        verify(chatAliasMapper).delete(any());
    }

    private ChatAlias row(long chatId, String kind, String aliases) {
        ChatAlias row = new ChatAlias();
        row.setChatId(chatId);
        row.setKind(kind);
        row.setAliases(aliases);
        return row;
    }
}
