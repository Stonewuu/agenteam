-- 所有编号作为字符串处理；累计内容和可读取的事件位置一起更新。
local meta = KEYS[1]
if redis.call('HGET', meta, 'generation') ~= ARGV[1] then
    return {'reset'}
end
if redis.call('HGET', meta, 'ready') ~= '1' then
    return {'reset'}
end
if redis.call('HGET', meta, 'server') ~= string.match(redis.call('INFO', 'server'), 'run_id:(%w+)') then
    return {'reset'}
end
local committed = ARGV[4] ~= '0'
local applied = redis.call('HGET', meta, 'applied')
local function compare(a, b)
    if #a ~= #b then
        return #a < #b and -1 or 1
    end
    if a == b then
        return 0
    end
    return a < b and -1 or 1
end
if compare(redis.call('GET', KEYS[2]), ARGV[17]) < 0 then
    return {'reset'}
end
if committed then
    if compare(ARGV[4], applied) <= 0 then
        return {'duplicate', ARGV[1], redis.call('GET', KEYS[2]), applied}
    end
    if applied ~= ARGV[5] then
        return {'database_order'}
    end
else
    if redis.call('GET', KEYS[9]) ~= ARGV[3]
        or redis.call('HGET', meta, 'activeRun') ~= ARGV[2] then
        return {'lease_lost'}
    end
end
local receipt = redis.call('HGET', KEYS[6], ARGV[6])
if receipt then
    return {'duplicate', ARGV[1], receipt, applied}
end
local previous = redis.call('HGET', KEYS[4], ARGV[7])
if ARGV[8] == 'delta' and previous ~= ARGV[9] then
    return {'block_version'}
end
if ARGV[8] == 'delta' and redis.call('EXISTS', KEYS[8]) == 0 then
    -- 原正文丢失后不能只用新片段重新建立正文，否则刷新会遗漏此前内容。
    redis.call('HSET', meta, 'ready', '0')
    return {'reset'}
end
if not previous and redis.call('HLEN', KEYS[3]) >= tonumber(ARGV[16]) then
    return {'capacity'}
end
local publish = true
if previous and ARGV[8] ~= 'delta' and compare(previous, ARGV[10]) >= 0 then
    publish = false
end
redis.call('HSET', meta, 'ready', '0')
if publish then
    if not previous then
        redis.call('HINCRBY', meta, 'directory', 1)
        redis.call('HINCRBY', meta, 'objectCount', 1)
    end
    redis.call('HSET', KEYS[3], ARGV[7], ARGV[11])
    redis.call('HSET', KEYS[4], ARGV[7], ARGV[10])
    if ARGV[8] == 'delta' then
        redis.call('APPEND', KEYS[8], ARGV[12])
    elseif ARGV[8] == 'block' then
        redis.call('SET', KEYS[8], ARGV[12])
    end
    if ARGV[8] == 'delta' or ARGV[8] == 'block' then
        redis.call('EXPIRE', KEYS[8], 86400)
        if not committed then
            redis.call('HSET', KEYS[10], ARGV[7], ARGV[10])
        end
    end
end
if committed then
    applied = ARGV[4]
    redis.call('HSET', meta, 'applied', applied)
    local dirty = redis.call('HGET', KEYS[10], ARGV[7])
    if dirty and compare(dirty, ARGV[10]) <= 0 then
        redis.call('HDEL', KEYS[10], ARGV[7])
    end
end
if ARGV[14] == 'start' then
    redis.call('HSET', meta, 'activeRun', ARGV[2])
elseif ARGV[14] == 'end' and redis.call('HGET', meta, 'activeRun') == ARGV[2] then
    redis.call('HSET', meta, 'activeRun', '')
    redis.call('DEL', KEYS[9])
end
if publish then
    redis.call('INCR', KEYS[2])
    local sequence = redis.call('GET', KEYS[2])
    local envelope = '{"generation":' .. cjson.encode(ARGV[1])
        .. ',"sequence":"' .. sequence .. '","databaseVersion":"' .. applied .. '",'
        .. string.sub(ARGV[13], 2)
    redis.call('XADD', KEYS[5], sequence .. '-0', 'data', envelope)
    redis.call('XTRIM', KEYS[5], 'MAXLEN', ARGV[15])
end
local sequence = redis.call('GET', KEYS[2])
redis.call('HSET', KEYS[6], ARGV[6], sequence)
redis.call('LPUSH', KEYS[7], ARGV[6])
if redis.call('LLEN', KEYS[7]) > 1024 then
    redis.call('HDEL', KEYS[6], redis.call('RPOP', KEYS[7]))
end
for i = 1, 7 do
    redis.call('EXPIRE', KEYS[i], 86400)
end
redis.call('EXPIRE', KEYS[10], 86400)
redis.call('HSET', meta, 'ready', '1')
return {'ok', ARGV[1], sequence, applied}
