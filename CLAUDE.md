# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A personal "QQ 消息智能助手" (QQ message smart assistant) built from multiple cooperating components.

**The repo is greenfield — only scaffolding exists.** The `design/` docs are the source of truth; read them before proposing structure or writing code.

| Doc | Contents |
|---|---|
| `design/prompt_init.md` | Original charter: components, 5 core features, rules, 5-phase workflow |
| `design/architecture.md` | Component boundaries, red lines, the 3 cross-cutting abstractions |
| `design/optimization_schemes.md` | Finalized decisions for the 5 schemes |

**Where the two conflict, `optimization_schemes.md` wins.** Its most important divergence from `prompt_init.md`: file I/O was moved out of Spring Boot into a **LangBot (Python) plugin**.

## Architecture

```
QQ ──► NapCat (QQ protocol)
         └──► LangBot (Python: routing, plugins, pre-interception, human-like delay, file I/O)
                 ├──► Dify (intent recognition, agent orchestration, decides "what to fill / whether to send")
                 └──► Spring Boot (state machines, persistence, scheduling, draft center, profiles)
                          └──► Vue3 (draft center, memos, memory console, settings)
```

### Red lines (do not cross)

| Red line | Meaning |
|---|---|
| **Business logic must not live in Dify prompts** | Dify does semantics / intent / dialogue-flow decisions only. State machines, persistence, and scheduling belong to Spring Boot; file I/O belongs to the LangBot plugin. |
| **Dify must not touch file streams** | Binary read/write is the LangBot plugin's job (`openpyxl` / `python-docx` / `pypdf`). Dify only decides *what* to fill. |
| **Style memory must not modify the System Prompt in real time** | Only periodic offline summarization updates the profile prompt. Only *fact* memory writes in real time. |
| **Spring Boot must not call NapCat directly** | Every QQ-side action (send message / send file) goes through LangBot. |

Core principle for file tasks: **LLM decides "what to fill" (flexible) + Python executes the read/write (generic)**. Dify returns structured `fills` + `missing`; the Python plugin reads the structure and writes back mechanically. No template library.

### Three cross-cutting abstractions (build once, in Phase 1)

Discussed independently in 5 schemes but deliberately unified to avoid 4× duplication:

1. **Unified draft center** — `draft` table, `type ∈ {REPLY, FILE, MEMO, FACT}`, `status ∈ {PENDING, CONFIRMED, REJECTED}`. `POST /api/drafts/{id}/confirm` dispatches by type (REPLY/FILE → tell LangBot to send; MEMO/FACT → write to the business table). PENDING never auto-expires.
2. **Unified message log** — `message_log` table (`direction IN/OUT`). Raw material for style summarization + end-to-end debugging. LangBot writes it on every message.
3. **LangBot active-send interface** — `send_message` (text) and `send_file` (binary) are **separate contracts, never conflated**.

**Project-wide consistency strategy:** any AI-produced output (reply / file / memo / fact) that AI might get wrong is downgradeable to a draft for human confirmation. Users can always hand-correct in Vue.

## Repo layout

Three parallel subprojects plus docs:

- `QQMessageAssistantJava/` — Spring Boot backend (business-logic layer)
- `QQMessageAssistantPython/` — LangBot plugin code (**only a `.venv` so far, no source**)
- `QQMessageAssistantVue/` — Vue3 + TypeScript frontend (**not scaffolded yet — empty dir**)
- `design/` — architecture docs (the source of truth)

## Commands

### Spring Boot — `QQMessageAssistantJava/`

Java 21 + system Maven. **There is no `mvnw` wrapper script** (only `.mvn/wrapper/maven-wrapper.properties`) — use the system `mvn`.

```bash
cd QQMessageAssistantJava
mvn spring-boot:run                                       # run (port 8080)
mvn test                                                  # all tests
mvn test -Dtest=QqMessageAssistantJavaApplicationTests     # single test class
mvn clean package                                         # build jar
```

Stack: Spring Boot 3.4.12, Java 21, `spring-boot-starter-web`, `konghq:unirest-java` 3.11.11 (HTTP client, currently used to call Dify). Config lives in `src/main/resources/application.yaml` (only `server.port: 8080` today).

`MessageController` (`POST /message`) is currently a thin pass-through to Dify's `/v1/chat-messages` — scaffolding, not the final API surface.

### Python / LangBot — `QQMessageAssistantPython/`

A venv exists (CPython 3.12.10). Use it by path rather than the global `python`:

```bash
QQMessageAssistantPython/.venv/Scripts/python.exe -m pip install <pkg>
QQMessageAssistantPython/.venv/Scripts/python.exe <script>.py
```

Planned file-format libraries: `openpyxl` (xlsx), `python-docx` (docx), `pypdf` (pdf).

### Vue — `QQMessageAssistantVue/`

Not scaffolded yet. Node/npm are available. When created, the stack is **Vite + Vue3 + TypeScript**, **Composition API with `<script setup>`** (per `prompt_init.md`). Planned pages: draft center, memo list, memory console (facts CRUD + style profile + summary-frequency settings).

## Environment notes (Windows / Git Bash)

- Shell is bash on Windows — use Unix syntax (`/dev/null`, forward slashes).
- `jq` is **not installed**.
- Avoid the global `python` / `python3` shims; use the subproject venv instead.

## Workflow

Work proceeds in 5 phases (see `prompt_init.md`): ① overall architecture + DB schema → ② Spring Boot APIs → ③ LangBot plugins → ④ Dify workflows/prompts → ⑤ Vue frontend + integration.

**Current state: design docs are complete; Phase 1 has not started yet.** Per `prompt_init.md`, define API contracts (JSON shapes) before writing code, and explain data flow before proposing complex architecture.
