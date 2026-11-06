package com.czx.qqmessageassistantjava.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Redis + Lua 令牌桶：一次脚本完成「读桶 → 按时间补 token → 扣减 → 写回」，
 * 多实例并发时也不会出现先读后写的竞态。
 */
@Service
public class RedisTokenBucket {

    /**
     * KEYS[1] 桶 key；ARGV[1] 容量；ARGV[2] 每秒补充量；
     * ARGV[3] 当前时间（秒，带小数）；ARGV[4] key 过期秒数。
     * 返回 1 = 放行，0 = 限流。
     */
    private static final String SCRIPT = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local ttl = tonumber(ARGV[4])

            local bucket = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(bucket[1])
            local ts = tonumber(bucket[2])
            if tokens == nil then
              tokens = capacity
              ts = now
            end

            local delta = math.max(0, now - ts)
            tokens = math.min(capacity, tokens + delta * refill)

            local allowed = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            end

            redis.call('HSET', key, 'tokens', tokens, 'ts', now)
            redis.call('EXPIRE', key, ttl)
            return allowed
            """;

    private static final DefaultRedisScript<Long> SCRIPT_OBJ =
            new DefaultRedisScript<>(SCRIPT, Long.class);

    private final StringRedisTemplate redis;

    public RedisTokenBucket(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean tryAcquire(String key, int capacity, double refillPerSecond, int ttlSeconds) {
        Long result = redis.execute(SCRIPT_OBJ, List.of(key),
                String.valueOf(capacity),
                String.valueOf(refillPerSecond),
                String.valueOf(System.currentTimeMillis() / 1000.0),
                String.valueOf(ttlSeconds));
        return result != null && result == 1L;
    }
}
