package com.czx.qqmessageassistantjava.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czx.qqmessageassistantjava.client.DifyWorkflowClient;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.dto.AskRequest;
import com.czx.qqmessageassistantjava.dto.ClarifyAnswer;
import com.czx.qqmessageassistantjava.entity.Contact;
import com.czx.qqmessageassistantjava.entity.FileTask;
import com.czx.qqmessageassistantjava.entity.Memo;
import com.czx.qqmessageassistantjava.entity.MemoryNote;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import com.czx.qqmessageassistantjava.mapper.FileTaskMapper;
import com.czx.qqmessageassistantjava.mapper.MemoMapper;
import com.czx.qqmessageassistantjava.mapper.MemoryNoteMapper;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import com.czx.qqmessageassistantjava.service.AppSettingService;
import com.czx.qqmessageassistantjava.service.AskService;
import com.czx.qqmessageassistantjava.service.ContactService;
import com.czx.qqmessageassistantjava.vo.AskResponse;
import com.czx.qqmessageassistantjava.vo.ClarifyCandidateVO;
import com.czx.qqmessageassistantjava.vo.ClarifyStateVO;
import com.czx.qqmessageassistantjava.vo.EvidenceVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一句话查回去：先认人，再凑候选集，最后让模型在候选集里作答。
 *
 * <p>分工是这条链路的全部：抽人名是语义（Dify），这个名字对应谁是数据（SQL），
 * 答案能不能站得住是校验（Spring）。任何一步都不许把整段聊天记录丢给模型让它自由发挥。
 */
@Service
public class AskServiceImpl implements AskService {

    private static final Logger log = LoggerFactory.getLogger(AskServiceImpl.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter MM_DD = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    static final int NOTE_LIMIT = 30;
    static final int MSG_LIMIT = 30;
    static final int MEMO_LIMIT = 15;
    static final int FILE_LIMIT = 15;
    /** 候选集总字符上限：先截断再问模型 */
    static final int CANDIDATE_CHARS = 12_000;
    static final int CONTENT_LIMIT = 200;
    static final int EVIDENCE_LIMIT = 5;
    static final int KNOWN_CONTACTS_LIMIT = 200;

    private final DifyWorkflowClient difyWorkflowClient;
    private final ContactService contactService;
    private final MemoryNoteMapper memoryNoteMapper;
    private final MessageLogMapper messageLogMapper;
    private final MemoMapper memoMapper;
    private final FileTaskMapper fileTaskMapper;
    private final AppSettingService appSettingService;

    public AskServiceImpl(DifyWorkflowClient difyWorkflowClient,
                          ContactService contactService,
                          MemoryNoteMapper memoryNoteMapper,
                          MessageLogMapper messageLogMapper,
                          MemoMapper memoMapper,
                          FileTaskMapper fileTaskMapper,
                          AppSettingService appSettingService) {
        this.difyWorkflowClient = difyWorkflowClient;
        this.contactService = contactService;
        this.memoryNoteMapper = memoryNoteMapper;
        this.messageLogMapper = messageLogMapper;
        this.memoMapper = memoMapper;
        this.fileTaskMapper = fileTaskMapper;
        this.appSettingService = appSettingService;
    }

    private record Candidate(String type, long id, String label, String content) {
        String key() {
            return type + ":" + id;
        }
    }

    @Override
    public AskResponse ask(AskRequest request) {
        String question = request.getQuestion() == null ? "" : request.getQuestion().trim();
        if (question.isEmpty()) {
            throw new BusinessException("问题不能为空");
        }

        // 0. 用户刚回答了「这是谁」：先记下来（source=USER，夜间任务不再覆盖）
        Map<String, Long> clarified = new LinkedHashMap<>();
        ClarifyAnswer answer = request.getClarify();
        if (answer != null && answer.getAlias() != null && !answer.getAlias().isBlank()) {
            if (answer.getSenderId() == null) {
                throw new BusinessException("缺少 QQ 号");
            }
            String alias = answer.getAlias().trim();
            contactService.addUser(answer.getSenderId(), alias, answer.getFrom());
            clarified.put(alias, answer.getSenderId());
        }

        // 1. 认人：模型只抽名字，对应谁由 SQL 说了算
        Map<String, Object> resolved = difyWorkflowClient.run("ask", Map.of(
                "stage", "resolve",
                "question", question,
                "known_contacts", knownContactsJson(),
                "owner_aliases", appSettingService.getOrDefault("owner_aliases", "")));
        List<String> entities = strings(resolved.get("entities"));
        List<String> keywords = strings(resolved.get("keywords"));
        String timeHint = text(resolved.get("time_hint"));

        Set<Long> subjects = new LinkedHashSet<>();
        Map<Long, String> names = new LinkedHashMap<>();
        for (String alias : entities) {
            if (clarified.containsKey(alias)) {
                subjects.add(clarified.get(alias));
                names.put(clarified.get(alias), alias);
                continue;
            }
            List<Contact> rows = contactService.findByAlias(alias);
            Set<Long> ids = new LinkedHashSet<>();
            rows.forEach(row -> ids.add(row.getSenderId()));
            if (ids.isEmpty()) {
                return needClarify(alias, List.of());
            }
            if (ids.size() > 1) {
                return needClarify(alias, rows);
            }
            Long senderId = ids.iterator().next();
            subjects.add(senderId);
            names.put(senderId, alias);
        }

        // 2. 候选集：结构化查询，先截断再问模型
        List<Candidate> candidates = gather(subjects, names, keywords);
        if (candidates.isEmpty()) {
            log.info("查询没有候选集，直接返回空：question={}", question);
            return response("", false, List.of(), null);
        }

        // 3. 答题
        Map<String, Object> outputs = difyWorkflowClient.run("ask", Map.of(
                "stage", "answer",
                "question", question,
                "candidates", renderCandidates(candidates),
                "time_hint", timeHint));
        String answerText = text(outputs.get("answer")).trim();
        List<String> used = stringsOrNull(outputs.get("used_ids"));

        // 4. 校验：引用必须落在候选集里；有结论就必须有证据
        Set<String> allowed = new LinkedHashSet<>();
        candidates.forEach(c -> allowed.add(c.key()));
        boolean invalid = used == null || !allowed.containsAll(used);
        boolean unverifiable = !answerText.isEmpty() && (used == null || used.isEmpty());
        if (invalid || unverifiable) {
            log.warn("查询结论被降级：used={} 不在候选集内或没有证据", used);
            return response("", true, evidence(candidates.subList(0,
                    Math.min(EVIDENCE_LIMIT, candidates.size()))), null);
        }
        return response(answerText, false, evidenceOf(used, candidates), null);
    }

    private List<Candidate> gather(Set<Long> subjects, Map<Long, String> names, List<String> keywords) {
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (Long subject : subjects) {
            for (MemoryNote note : memoryNoteMapper.selectList(new LambdaQueryWrapper<MemoryNote>()
                    .eq(MemoryNote::getSubjectSenderId, subject)
                    .orderByDesc(MemoryNote::getId)
                    .last("LIMIT " + NOTE_LIMIT))) {
                add(out, seen, note(note, names.get(subject)));
            }
            for (MessageLog log : messageLogMapper.selectList(new LambdaQueryWrapper<MessageLog>()
                    .eq(MessageLog::getSenderId, subject)
                    .eq(MessageLog::getDirection, "IN")
                    .orderByDesc(MessageLog::getId)
                    .last("LIMIT " + MSG_LIMIT))) {
                add(out, seen, message(log));
            }
        }

        for (String keyword : keywords) {
            for (MemoryNote note : memoryNoteMapper.selectList(new LambdaQueryWrapper<MemoryNote>()
                    .and(w -> w.like(MemoryNote::getValue, keyword)
                            .or().like(MemoryNote::getPredicate, keyword))
                    .orderByDesc(MemoryNote::getId)
                    .last("LIMIT " + NOTE_LIMIT))) {
                add(out, seen, note(note, null));
            }
            for (MessageLog log : messageLogMapper.selectList(new LambdaQueryWrapper<MessageLog>()
                    .eq(MessageLog::getDirection, "IN")
                    .like(MessageLog::getContent, keyword)
                    .orderByDesc(MessageLog::getId)
                    .last("LIMIT " + MSG_LIMIT))) {
                add(out, seen, message(log));
            }
            for (Memo memo : memoMapper.selectList(new LambdaQueryWrapper<Memo>()
                    .and(w -> w.like(Memo::getTitle, keyword).or().like(Memo::getContent, keyword))
                    .orderByDesc(Memo::getId)
                    .last("LIMIT " + MEMO_LIMIT))) {
                add(out, seen, new Candidate("memo", memo.getId(),
                        "备忘 · " + nullToEmpty(memo.getTitle()),
                        truncate(flatten(memo.getContent()), CONTENT_LIMIT)));
            }
            for (FileTask file : fileTaskMapper.selectList(new LambdaQueryWrapper<FileTask>()
                    .like(FileTask::getFileName, keyword)
                    .orderByDesc(FileTask::getId)
                    .last("LIMIT " + FILE_LIMIT))) {
                add(out, seen, new Candidate("file", file.getId(),
                        "文件 · " + nullToEmpty(file.getFileName()),
                        truncate(flatten(file.getRequest()), CONTENT_LIMIT)));
            }
        }

        // 总量截断：保住顺序，超了就不要后面的
        List<Candidate> kept = new ArrayList<>();
        int chars = 0;
        for (Candidate candidate : out) {
            int size = candidate.label().length() + candidate.content().length() + 16;
            if (chars + size > CANDIDATE_CHARS) {
                break;
            }
            chars += size;
            kept.add(candidate);
        }
        return kept;
    }

    private void add(List<Candidate> out, Set<String> seen, Candidate candidate) {
        if (candidate.id() <= 0 || !seen.add(candidate.key())) {
            return;
        }
        out.add(candidate);
    }

    private Candidate note(MemoryNote note, String subjectAlias) {
        String who = subjectAlias == null || subjectAlias.isBlank() ? "记录" : subjectAlias;
        return new Candidate("note", note.getId(),
                who + " · " + nullToEmpty(note.getPredicate()),
                truncate(flatten(note.getValue()), CONTENT_LIMIT));
    }

    private Candidate message(MessageLog log) {
        String chat = nullToEmpty(log.getChatName()).isBlank() ? "私聊" : log.getChatName();
        String who = nullToEmpty(log.getSenderName()).isBlank()
                ? String.valueOf(log.getSenderId()) : log.getSenderName();
        String when = log.getCreatedAt() == null ? "" : MM_DD.format(log.getCreatedAt());
        return new Candidate("msg", log.getId(),
                chat + " · " + when + " · " + who,
                truncate(flatten(log.getContent()), CONTENT_LIMIT));
    }

    private String renderCandidates(List<Candidate> candidates) {
        StringBuilder sb = new StringBuilder();
        for (Candidate candidate : candidates) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(candidate.key()).append('|')
                    .append(candidate.label()).append('|')
                    .append(candidate.content());
        }
        return sb.toString();
    }

    private List<EvidenceVO> evidence(List<Candidate> candidates) {
        List<EvidenceVO> out = new ArrayList<>();
        for (Candidate candidate : candidates) {
            out.add(toEvidence(candidate));
        }
        return out;
    }

    private List<EvidenceVO> evidenceOf(List<String> used, List<Candidate> candidates) {
        List<EvidenceVO> out = new ArrayList<>();
        for (String key : used) {
            candidates.stream()
                    .filter(c -> c.key().equals(key))
                    .findFirst()
                    .ifPresent(c -> out.add(toEvidence(c)));
        }
        return out;
    }

    private EvidenceVO toEvidence(Candidate candidate) {
        EvidenceVO vo = new EvidenceVO();
        vo.setType(candidate.type());
        vo.setId(String.valueOf(candidate.id()));
        vo.setLabel(candidate.label());
        vo.setContent(candidate.content());
        return vo;
    }

    private AskResponse needClarify(String alias, List<Contact> rows) {
        ClarifyStateVO state = new ClarifyStateVO();
        state.setAlias(alias);
        List<ClarifyCandidateVO> candidates = new ArrayList<>();
        for (Contact row : rows) {
            ClarifyCandidateVO candidate = new ClarifyCandidateVO();
            candidate.setSenderId(row.getSenderId());
            candidate.setAlias(row.getAlias());
            candidate.setRelation(row.getRelation());
            candidate.setHint("QQ " + row.getSenderId());
            candidates.add(candidate);
        }
        state.setCandidates(candidates);
        return response("", false, List.of(), state);
    }

    private AskResponse response(String answer, boolean degraded,
                                 List<EvidenceVO> evidence, ClarifyStateVO needClarify) {
        AskResponse response = new AskResponse();
        response.setAnswer(answer == null ? "" : answer);
        response.setDegraded(degraded);
        response.setEvidence(evidence);
        response.setNeedClarify(needClarify);
        return response;
    }

    private String knownContactsJson() {
        List<Contact> known = contactService.list(new LambdaQueryWrapper<Contact>()
                .orderByDesc(Contact::getUpdatedAt)
                .last("LIMIT " + KNOWN_CONTACTS_LIMIT));
        List<Map<String, String>> rows = known.stream().map(c -> Map.of(
                "senderId", String.valueOf(c.getSenderId()),
                "alias", nullToEmpty(c.getAlias()),
                "relation", nullToEmpty(c.getRelation()))).toList();
        try {
            return JSON.writeValueAsString(rows);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 字符串数组；形状不对就当没认出来人 */
    private List<String> strings(Object raw) {
        List<String> parsed = stringsOrNull(raw);
        return parsed == null ? List.of() : parsed;
    }

    private List<String> stringsOrNull(Object raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (raw instanceof Collection<?> collection) {
            for (Object o : collection) {
                if (o != null && !o.toString().isBlank()) {
                    out.add(o.toString().trim());
                }
            }
            return out;
        }
        String text = raw.toString().trim();
        if (text.isEmpty()) {
            return List.of();
        }
        try {
            List<Object> values = JSON.readValue(text, new TypeReference<List<Object>>() {
            });
            for (Object value : values) {
                if (value != null && !value.toString().isBlank()) {
                    out.add(value.toString().trim());
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("Dify 返回的字符串数组不合法：{}", text);
            return null;
        }
    }

    private String text(Object raw) {
        return raw == null ? "" : raw.toString();
    }

    private String flatten(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]+", " ").trim();
    }

    private String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
