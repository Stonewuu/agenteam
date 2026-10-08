-- 初始化只在持有重建资格时执行；旧连接使用不同的生命周期标识。
if redis.call('GET', KEYS[9]) ~= ARGV[1] then
    return {'busy'}
end
local current = redis.call('HGET', KEYS[1], 'generation')
local server = string.match(redis.call('INFO', 'server'), 'run_id:(%w+)')
if current and redis.call('HGET', KEYS[1], 'ready') == '1'
    and redis.call('HGET', KEYS[1], 'server') == server and ARGV[5] ~= current then
    return {'existing', current}
end
for i = 1, 8 do
    redis.call('DEL', KEYS[i])
end
redis.call('SET', KEYS[2], '0')
redis.call('HSET', KEYS[1], 'generation', ARGV[2], 'base', ARGV[3], 'applied', ARGV[3],
    'activeRun', ARGV[4], 'directory', '0', 'objectCount', '0', 'ready', '1', 'server', server)
redis.call('EXPIRE', KEYS[1], 86400)
redis.call('EXPIRE', KEYS[2], 86400)
return {'ok', ARGV[2]}
