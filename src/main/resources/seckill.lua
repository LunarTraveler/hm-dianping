local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

if (redis.call('sismember', orderKey, userId) == 1) then
    -- 这个用户已购买过返回2
    return 2
end
if (tonumber(redis.call('get', stockKey)) <= 0) then
    -- 库存不足返回1
    return 1
end
-- 其实这里就已经确保了是可以下单的，否则的话就造成了数据不一致的问题（实现了自动的去重幂等性）
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)

-- 资格认证通过并且库存减一
return 0