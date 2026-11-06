package com.czx.qqmessageassistantjava.service;

public interface MemoryJobService {

    /** 处理游标之后的一批消息；返回写入的 note 条数，0 表示没开/没料/结果不可用 */
    int runOnce();

    /** 游标归零后一批批跑完整个历史；返回写入的事实条数合计 */
    int rebuild();

    /**
     * 从当前游标一批批往前赶，直到游标不再前进或达到批数上限；返回写入的事实条数合计。
     *
     * <p>与 {@link #rebuild()} 的区别：<b>不重置游标</b>。用于「宕机错过了夜间归纳，
     * 重启后把积压的消息补算掉」——已处理过的不会重跑，只是把落下的批次赶上。
     */
    int catchUp();
}
