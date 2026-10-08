if redis.call('HGET', KEYS[1], 'ready') ~= '1'
    or redis.call('HGET', KEYS[1], 'generation') ~= ARGV[1]
    or redis.call('HGET', KEYS[1], 'server') ~= string.match(redis.call('INFO', 'server'), 'run_id:(%w+)')
    or redis.call('HGET', KEYS[1], 'activeRun') ~= ARGV[2] then
    return 0
end
local previous = nil
if redis.call('HGET', KEYS[1], 'leaseRun') == ARGV[2] then
    previous = redis.call('HGET', KEYS[1], 'leaseVersion')
end
if previous and (#previous > #ARGV[3] or (#previous == #ARGV[3] and previous > ARGV[3])) then
    return 0
end
if redis.call('HGET', KEYS[1], 'closedRun') == ARGV[2] then
    local closed = redis.call('HGET', KEYS[1], 'closedVersion')
    if #closed > #ARGV[3] or (#closed == #ARGV[3] and closed >= ARGV[3]) then
        return 0
    end
end
if tonumber(ARGV[5]) <= 0 then
    return 0
end
redis.call('HSET', KEYS[1], 'leaseRun', ARGV[2], 'leaseVersion', ARGV[3])
redis.call('SET', KEYS[2], ARGV[4], 'PX', ARGV[5])
redis.call('EXPIRE', KEYS[1], 86400)
for i = 3, #KEYS do
    redis.call('EXPIRE', KEYS[i], 86400)
end
return 1
