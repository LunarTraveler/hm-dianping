-- tryLock
local key = KEYS[1]
local threadId = ARGV[1]
local releaseTime = ARGV[2]

if (redis.call('exist', key) == 0) then
    -- 锁不存在，创建一个锁并设置有效期
    redis.call('hincrby', key, threadId, 1);
    redis.call('expire', key, releaseTime);
    return 1;
end;
-- 锁存在的(判断是不是自己的锁)
if (redis.call('hexist', key, threadId) == 1) then
    -- 锁是自己的
    redis.call('hincrby', key, threadId, 1);
    redis.call('expire', key, releaseTime);
    return 1;
end;
return nil; -- 锁不是自己的



