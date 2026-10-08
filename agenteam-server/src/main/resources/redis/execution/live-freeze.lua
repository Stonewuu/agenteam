-- 结束执行前禁止旧资格继续发送，避免读取部分结果后又收到旧片段。
if redis.call('HGET', KEYS[1], 'ready') ~= '1'
    or redis.call('HGET', KEYS[1], 'activeRun') ~= ARGV[1] then
    return 0
end
redis.call('HSET', KEYS[1], 'closedRun', ARGV[1], 'closedVersion', ARGV[2])
redis.call('DEL', KEYS[2])
return 1
