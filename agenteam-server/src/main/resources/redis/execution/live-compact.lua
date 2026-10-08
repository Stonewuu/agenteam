-- 只有全部累计块已保存才移交给数据库；旧数据库快照必须重新读取。
if redis.call('HGET', KEYS[1], 'ready') ~= '1'
    or redis.call('HGET', KEYS[1], 'server') ~= string.match(redis.call('INFO', 'server'), 'run_id:(%w+)')
    or redis.call('HGET', KEYS[1], 'generation') ~= ARGV[1]
    or redis.call('HGET', KEYS[1], 'directory') ~= ARGV[2]
    or redis.call('HLEN', KEYS[4]) ~= 0 then
    return 0
end
redis.call('HSET', KEYS[1], 'base', redis.call('HGET', KEYS[1], 'applied'))
redis.call('HINCRBY', KEYS[1], 'directory', 1)
redis.call('HSET', KEYS[1], 'objectCount', '0')
redis.call('DEL', KEYS[2], KEYS[3])
for i = 5, #KEYS do
    redis.call('DEL', KEYS[i])
end
return 1
