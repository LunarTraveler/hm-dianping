local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. userId

if (tonumber(redis.call('get', stockKey)) <= 0) then
    -- 库存不足返回1
    return 1
end
if (redis.call('sismember', orderKey, userId) == 1) then
    -- 这个用户已购买过返回2
    return 2
end
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)

-- 资格认证通过并且库存减一
return 0