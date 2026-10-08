if redis.call('HGET', KEYS[1], 'ready') ~= '1'
    or redis.call('HGET', KEYS[1], 'server') ~= string.match(redis.call('INFO', 'server'), 'run_id:(%w+)') then
    return {}
end
if redis.call('HLEN', KEYS[2]) ~= tonumber(redis.call('HGET', KEYS[1], 'objectCount')) then
    redis.call('HSET', KEYS[1], 'ready', '0')
    return {}
end
local result = {redis.call('HGET', KEYS[1], 'generation'), redis.call('HGET', KEYS[1], 'directory')}
for _, id in ipairs(redis.call('HKEYS', KEYS[2])) do
    table.insert(result, id)
end
return result
