-- 键目录与内容在同次脚本执行中校验，返回的正文包含到所返回的序号。
if redis.call('HGET', KEYS[1], 'ready') ~= '1'
    or redis.call('HGET', KEYS[1], 'server') ~= string.match(redis.call('INFO', 'server'), 'run_id:(%w+)')
    or redis.call('HGET', KEYS[1], 'generation') ~= ARGV[1]
    or redis.call('HGET', KEYS[1], 'directory') ~= ARGV[2] then
    return {}
end
local result = {ARGV[1], redis.call('GET', KEYS[2]), redis.call('HGET', KEYS[1], 'base'),
    redis.call('HGET', KEYS[1], 'applied'), redis.call('HGET', KEYS[1], 'activeRun')}
for i = 3, #ARGV do
    local value = redis.call('HGET', KEYS[3], ARGV[i])
    if not value then
        return {}
    end
    local text = redis.call('GET', KEYS[i + 1])
    if string.sub(ARGV[i], 1, 6) == 'block:' and not text then
        redis.call('HSET', KEYS[1], 'ready', '0')
        return {}
    end
    table.insert(result, ARGV[i])
    table.insert(result, value)
    table.insert(result, text or '')
end
return result
