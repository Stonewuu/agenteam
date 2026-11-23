-- 完整业务记录代替缺失通知；只保留当前执行中尚未保存的正文。
if redis.call('GET', KEYS[9]) ~= ARGV[1]
    or redis.call('HGET', KEYS[1], 'generation') ~= ARGV[2]
    or redis.call('GET', KEYS[2]) ~= ARGV[3]
    or redis.call('HGET', KEYS[1], 'applied') ~= ARGV[4]
    or redis.call('HGET', KEYS[1], 'ready') ~= '1'
    or redis.call('HGET', KEYS[1], 'server') ~= string.match(redis.call('INFO', 'server'), 'run_id:(%w+)') then
    return {'changed'}
end
local function greater(a, b)
    return #a > #b or (#a == #b and a > b)
end
if not greater(ARGV[5], ARGV[4]) then
    return {'ok'}
end
local saved = cjson.decode(ARGV[8])
local objects = cjson.decode(ARGV[11])
redis.call('HSET', KEYS[1], 'ready', '0')
for index, object in ipairs(objects) do
    local revision = redis.call('HGET', KEYS[4], object)
    local keep = ARGV[7] ~= '' and string.sub(object, 1, #ARGV[7]) == ARGV[7]
        and revision and greater(revision, saved[object] or '0')
    if not keep then
        redis.call('HDEL', KEYS[3], object)
        redis.call('HDEL', KEYS[4], object)
        redis.call('HDEL', KEYS[8], object)
        redis.call('DEL', KEYS[10 + index])
    end
end
if redis.call('HGET', KEYS[1], 'activeRun') ~= ARGV[6] then
    redis.call('DEL', KEYS[10])
end
redis.call('HINCRBY', KEYS[1], 'directory', 1)
redis.call('HSET', KEYS[1], 'base', ARGV[5], 'applied', ARGV[5], 'activeRun', ARGV[6],
    'objectCount', tostring(redis.call('HLEN', KEYS[3])))
redis.call('INCR', KEYS[2])
local sequence = redis.call('GET', KEYS[2])
local envelope = '{"generation":' .. cjson.encode(ARGV[2])
    .. ',"sequence":"' .. sequence .. '","databaseVersion":"' .. ARGV[5] .. '",'
    .. string.sub(ARGV[9], 2)
redis.call('XADD', KEYS[5], sequence .. '-0', 'data', envelope)
redis.call('XTRIM', KEYS[5], 'MAXLEN', ARGV[10])
for index = 1, 8 do
    redis.call('EXPIRE', KEYS[index], 86400)
end
redis.call('HSET', KEYS[1], 'ready', '1')
return {'ok'}
