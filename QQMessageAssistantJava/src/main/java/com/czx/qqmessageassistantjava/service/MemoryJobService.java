package com.czx.qqmessageassistantjava.service;

public interface MemoryJobService {

    /** 处理游标之后的一批消息；返回写入的 note 条数，0 表示没开/没料/结果不可用 */
    int runOnce();

    /** 游标归零后一批批跑完整个历史；返回写入的事实条数合计 */
    int rebuild();
}
