-- unLock
local key = KEYS[1]
local threadId = ARGV[1]
local releaseTime = ARGV[2]

-- 判断锁是否是自己的
if (redis.call('hexist', key, threadId) == 0) then
    -- 不是自己的锁
    return nil;
end;
local count = redis.call('hincrby', key, threadId, -1);
if (count > 0) then
    -- 说明还有其他持有这个锁
    redis.call('expire', key, releaseTime);
    return 0;
else
    redis.call('del', key);
    return 1;
end;
return nil;