if redis.call('HGET', KEYS[1], 'ready') ~= '1'
    or redis.call('HGET', KEYS[1], 'server') ~= string.match(redis.call('INFO', 'server'), 'run_id:(%w+)') then
    return {}
end
local sequence = redis.call('GET', KEYS[2])
local base = redis.call('HGET', KEYS[1], 'base')
local applied = redis.call('HGET', KEYS[1], 'applied')
local generation = redis.call('HGET', KEYS[1], 'generation')
local active = redis.call('HGET', KEYS[1], 'activeRun')
local count = tonumber(redis.call('HGET', KEYS[1], 'objectCount') or '')
local function number(value)
    return value and (value == '0' or string.match(value, '^[1-9]%d*$'))
        and (#value < 19 or (#value == 19 and value <= '9223372036854775807'))
end
if not number(sequence) or not number(base) or not number(applied) or not generation or not active
    or not count or redis.call('HLEN', KEYS[3]) ~= count or redis.call('HLEN', KEYS[4]) ~= count then
    redis.call('HSET', KEYS[1], 'ready', '0')
    return {}
end
return {generation, sequence, base, applied, active}
