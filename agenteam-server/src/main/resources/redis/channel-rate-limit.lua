-- 同一个应用的键使用相同的 Redis 集群分组。按服务端时钟一次检查并占用全部额度。
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local wait = 0
for i, key in ipairs(KEYS) do
    local start = 2 + (i - 1) * 4
    local mode = ARGV[start]
    local window = tonumber(ARGV[start + 1]) * 1000
    local maximum = tonumber(ARGV[start + 2])
    if mode == 'day' then
        local offset = tonumber(ARGV[start + 3]) * 1000
        local day = math.floor((now + offset) / 86400000)
        local saved = redis.call('HMGET', key, 'day', 'count')
        if tonumber(saved[1]) == day and tonumber(saved[2]) >= maximum then
            wait = math.max(wait, (day + 1) * 86400000 - offset - now)
        end
    else
        redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)
        if redis.call('ZCARD', key) >= maximum then
            local first = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
            wait = math.max(wait, tonumber(first[2]) + window - now)
        end
    end
end
if wait > 0 then
    return wait
end
for i, key in ipairs(KEYS) do
    local start = 2 + (i - 1) * 4
    if ARGV[start] == 'day' then
        local day = math.floor((now + tonumber(ARGV[start + 3]) * 1000) / 86400000)
        local saved = redis.call('HMGET', key, 'day', 'count')
        local count = tonumber(saved[1]) == day and tonumber(saved[2]) or 0
        redis.call('HSET', key, 'day', day, 'count', count + 1)
        redis.call('PEXPIRE', key, 172800000)
    else
        redis.call('ZADD', key, now, ARGV[1])
        redis.call('PEXPIRE', key, tonumber(ARGV[start + 1]) * 1000 + 1000)
    end
end
return 0
