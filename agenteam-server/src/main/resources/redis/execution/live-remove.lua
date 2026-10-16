-- 持有初始化资格并确认内容目录未变化后清理；数据库删除完成前保留该资格。
if redis.call('GET', KEYS[1]) ~= ARGV[1] or redis.call('HLEN', KEYS[2]) ~= #ARGV - 1 then
    return 0
end
for i = 2, #ARGV do
    if redis.call('HEXISTS', KEYS[2], ARGV[i]) ~= 1 then
        return 0
    end
end
for i = 2, #KEYS do
    redis.call('DEL', KEYS[i])
end
return 1
