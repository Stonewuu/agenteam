-- 同一业务事务的变化一起更新，累计内容与数据库版本不能只完成其中一部分。
local meta = KEYS[1]
if redis.call('HGET', meta, 'generation') ~= ARGV[1]
    or redis.call('HGET', meta, 'ready') ~= '1'
    or redis.call('HGET', meta, 'server') ~= string.match(redis.call('INFO', 'server'), 'run_id:(%w+)') then
    return {'reset'}
end
local function compare(a, b)
    if #a ~= #b then
        return #a < #b and -1 or 1
    end
    if a == b then
        return 0
    end
    return a < b and -1 or 1
end
local committed = ARGV[3] ~= '0'
local applied = redis.call('HGET', meta, 'applied')
if compare(redis.call('GET', KEYS[2]), ARGV[5]) < 0 then
    return {'reset'}
end
if committed then
    if compare(ARGV[3], applied) <= 0 then
        return {'duplicate', ARGV[1], redis.call('GET', KEYS[2]), applied}
    end
    if applied ~= ARGV[4] then
        return {'database_order'}
    end
end
local changes = cjson.decode(ARGV[8])
local count = redis.call('HLEN', KEYS[3])
local versions = {}
-- 在修改前检查整批内容，后面的无效内容不能留下前半批更新。
for index, change in ipairs(changes) do
    local body = KEYS[7 + index * 2]
    local lease = KEYS[8 + index * 2]
    if not committed and (redis.call('GET', lease) ~= ARGV[2]
        or redis.call('HGET', meta, 'activeRun') ~= change.runId) then
        return {'lease_lost'}
    end
    local previous = versions[change.objectId] or redis.call('HGET', KEYS[4], change.objectId)
    if change.kind == 'delta' then
        if previous ~= change.expectedRevision then
            local receipt = redis.call('HGET', KEYS[6], change.id)
            if #changes == 1 and receipt then
                return {'duplicate', ARGV[1], receipt, applied}
            end
            return {'block_version'}
        end
        if redis.call('EXISTS', body) == 0 then
            redis.call('HSET', meta, 'ready', '0')
            return {'reset'}
        end
    end
    if not previous then
        count = count + 1
        if count > tonumber(ARGV[7]) then
            return {'capacity'}
        end
    end
    versions[change.objectId] = change.revision
end
redis.call('HSET', meta, 'ready', '0')
if committed then
    applied = ARGV[3]
end
for index, change in ipairs(changes) do
    local body = KEYS[7 + index * 2]
    local lease = KEYS[8 + index * 2]
    local previous = redis.call('HGET', KEYS[4], change.objectId)
    local publish = not previous or change.kind == 'delta' or compare(previous, change.revision) < 0
    if redis.call('HEXISTS', KEYS[6], change.id) == 1 then
        publish = false
    end
    if publish then
        if not previous then
            redis.call('HINCRBY', meta, 'directory', 1)
            redis.call('HINCRBY', meta, 'objectCount', 1)
        end
        redis.call('HSET', KEYS[3], change.objectId, change.state)
        redis.call('HSET', KEYS[4], change.objectId, change.revision)
        if change.kind == 'delta' then
            redis.call('APPEND', body, change.text)
        elseif change.kind == 'block' then
            redis.call('SET', body, change.text)
        end
        if change.kind == 'delta' or change.kind == 'block' then
            redis.call('EXPIRE', body, 86400)
            if not committed then
                redis.call('HSET', KEYS[8], change.objectId, change.revision)
            end
        end
    end
    if committed then
        local dirty = redis.call('HGET', KEYS[8], change.objectId)
        if dirty and compare(dirty, change.revision) <= 0 then
            redis.call('HDEL', KEYS[8], change.objectId)
        end
    end
    if change.activeChange == 'start' then
        redis.call('HSET', meta, 'activeRun', change.runId)
    elseif change.activeChange == 'end' and redis.call('HGET', meta, 'activeRun') == change.runId then
        redis.call('HSET', meta, 'activeRun', '')
        redis.call('DEL', lease)
    end
    if publish then
        redis.call('INCR', KEYS[2])
        local sequence = redis.call('GET', KEYS[2])
        local envelope = '{"generation":' .. cjson.encode(ARGV[1])
            .. ',"sequence":"' .. sequence .. '","databaseVersion":"' .. applied .. '",'
            .. string.sub(change.envelope, 2)
        redis.call('XADD', KEYS[5], sequence .. '-0', 'data', envelope)
    end
    local sequence = redis.call('GET', KEYS[2])
    redis.call('HSET', KEYS[6], change.id, sequence)
    redis.call('LPUSH', KEYS[7], change.id)
    if redis.call('LLEN', KEYS[7]) > 1024 then
        redis.call('HDEL', KEYS[6], redis.call('RPOP', KEYS[7]))
    end
end
redis.call('XTRIM', KEYS[5], 'MAXLEN', ARGV[6])
for index = 1, 8 do
    redis.call('EXPIRE', KEYS[index], 86400)
end
redis.call('HSET', meta, 'applied', applied, 'ready', '1')
return {'ok', ARGV[1], redis.call('GET', KEYS[2]), applied}
