<div align="center">

# 🤖 QQ 消息智能助手 (QQ Smart Assistant)

**基于多 Agent 协同与严格组件边界的下一代 QQ 私人助理 / 数字分身**

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen.svg)]()
[![Vue](https://img.shields.io/badge/Vue-3.x-42b883.svg)]()
[![Python](https://img.shields.io/badge/Python-3.10+-3776AB.svg)]()
[![Dify](https://img.shields.io/badge/Dify-Agent%20Workflow-2563eb.svg)]()

[核心特性](#-核心特性) • [系统架构](#-系统架构) • [设计理念](#-核心设计理念) • [技术栈](#-技术栈) • [快速开始](#-快速开始) • [路线图](#-路线图)

</div>

---

## 🌟 项目简介

这不仅仅是一个 QQ 聊天机器人，而是一个**懂你习惯、帮你处理琐事、管理日程的“数字分身”**。
本项目摒弃了“把所有逻辑塞给大模型”的粗暴做法，采用**多组件协同与严格的职责边界**设计。让 LLM 专注于语义理解与灵活决策，让传统后端专注于状态管理与文件 I/O，打造了一个高可用、防漂移、可解释的 AI Agent 落地最佳实践。

---

## ✨ 核心特性

### 1. 🎭 拟人私聊与草稿确认 (Human-like Chat & Draft Mode)
- **拟人化延迟**：拒绝机械秒回，根据字数动态计算阅读与打字时间，加入随机抖动，完美模拟真人节奏。
- **置信度路由**：AI 生成的回复不盲信。低置信度或非紧急回复自动转入“统一草稿中心”，由用户在 Vue 前端一键确认或修改后发送。

### 2. 📢 智能统一回复与防重 (Smart Auto-Reply)
- **前置拦截**：群聊收集信息（如学号、青年大学习）通过“关键词+正则”在 LangBot 层前置拦截， bypass Dify 节省 Token。
- **状态机防重**：Spring Boot 维护任务状态表，拦截命中后仍校验状态，彻底杜绝重复回复。

### 3. 📅 分级备忘录与动态提醒 (Graded Memo & Reminders)
- **自动提取**：精准识别群聊/私聊中的邀约、通知，自动抽取结构化信息。
- **5级重要性分级**：从“考试答辩(5级)”到“纯信息存档(1级)”，匹配不同的提前量与提醒语气。
- **双端闭环**：支持 QQ 内直接回复“完成/取消”，或 Vue 前端操作，状态实时同步。

### 4. 📁 复杂文件处理 (File Processing Backend)
- **LLM 决策 + Python 执行**：Dify 负责决策“填什么”，LangBot 插件 (Python) 负责机械地“读与写” (支持 xlsx/docx/pdf)。
- **自然反问**：遇到画像缺失字段，Agent 会在 QQ 中自然地向用户反问补充，而非直接报错。

### 5. 🧠 Agent 持续学习与记忆分离 (Continuous Learning)
- **事实与风格分离**：
  - **事实记忆**（学号/姓名）：实时抽取，高置信度直接入库，低置信度转草稿。
  - **风格记忆**（语气/性格）：**严禁实时修改 System Prompt 防漂移**！采用定期离线总结机制，提炼风格特征后更新画像 Prompt。
- **记忆总控台**：Vue 端提供透明的记忆管理面板，用户可随时增删改事实记忆，手动干预风格画像。

---

## 🏗️ 系统架构

项目采用清晰的分层架构，坚守 **“组件边界不可逾越”** 的红线。

```mermaid
graph TD
    User((QQ 用户)) -->|QQ 协议| NapCat[NapCat 协议端]
    
    subgraph 消息接入与执行层
        NapCat -->|事件流| LangBot[LangBot Python]
        LangBot -->|拟人延迟/前置拦截/文件IO| LangBot
        LangBot -.->|主动发送 API| SpringBoot
    end

    subgraph AI 大脑层
        LangBot -->|复杂意图/对话流| Dify[Dify Agent]
        Dify -->|Tool 调用| SpringBoot
        Dify -->|决策填什么/发不发| LangBot
    end

    subgraph 业务逻辑与持久层
        SpringBoot[Spring Boot Java] -->|状态机/草稿中心/定时调度| DB[(MySQL/PostgreSQL)]
        SpringBoot -->|REST/WS| Vue[Vue3 前端]
    end

    subgraph 交互展示层
        Vue -->|草稿确认/记忆总控/备忘管理| User((用户))
    end