local oneLevelKey = KEYS[1]
local twoLevelKey = KEYS[2]
local countKey = KEYS[3]

local thirtyMinuteAgo = ARGV[1]
local fiveMinuteAgo = ARGV[2]
local oneMinuteAgo = ARGV[3]
local currentTime = ARGV[4]

-- 对于一级限制的判断
local oneLevelLimit = redis.call('get', oneLevelKey)
if (oneLevelLimit == '1') then
    return {'err', "您在过去5分钟之内3次验证失败，需要等5分钟后再请求"}
end

-- 对于二级限制的判断
local twoLevelLimit = redis.call('get', twoLevelKey)
if (twoLevelLimit == '1') then
    return {'err', "您在过去30分钟之内5次验证失败，需要等30分钟后再请求"}
end

-- 检查一分钟之内发送验证码的次数
local oneMinuteCount = redis.call('zcount', countKey, oneMinuteAgo, currentTime)
if (oneMinuteCount >= 1) then
    return {'err', "距离上次发送时间不足1分钟，请1分钟后重试"}
end

-- 检查过去5分钟的发送次数（一级限制）
local fiveMinuteCount = redis.call('zcount', countKey, fiveMinuteAgo, currentTime)
if (fiveMinuteCount >= 3) then
    redis.call('set', oneLevelKey, '1', 'EX', 300)
    return {'err', "您在过去5分钟之内3次验证失败，需要等5分钟后再请求"}
end

-- 检查过去30分钟的发送次数（二级限制）
local thirtyMinuteCount = redis.call('zcount', countKey, thirtyMinuteAgo, currentTime)
if (thirtyMinuteCount >= 5) then
    redis.call('set', twoLevelKey, '1', 'EX', 1800)
    return {'err', "您在过去30分钟之内5次验证失败，需要等30分钟后再请求"}
end

-- 这个是在发送邮箱之后的才有的，其实不应该在脚本中执行
-- redis.call('ZADD', countKey, tonumber(currentTime), email)

return {'ok', "ok"}







