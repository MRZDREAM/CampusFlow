local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local user = ARGV[1]
local token = ARGV[2]
local budget = tonumber(ARGV[3])
local lifetime = tonumber(ARGV[4])

for _, expired in ipairs(redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', now)) do
    redis.call('ZREM', KEYS[1], expired)
    redis.call('HDEL', KEYS[2], expired)
end

local owner = redis.call('HGET', KEYS[2], user)
if owner then
    if owner == token then return 2 end
    return -1
end
if redis.call('ZCARD', KEYS[1]) >= budget then return 0 end

redis.call('HSET', KEYS[2], user, token)
redis.call('ZADD', KEYS[1], now + lifetime, user)
redis.call('PEXPIRE', KEYS[1], lifetime * 2)
redis.call('PEXPIRE', KEYS[2], lifetime * 2)
return 1
