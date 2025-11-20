--参数
--1.优惠券 id
local voucherId = ARGV[1];
--2.用户 id
local userId = ARGV[2];
--3.库存key
local stockKey = "seckill:stock:" .. voucherId;
--4.订单key
local orderKey = "seckill:order:" .. userId;

--判断库存是否充足
if (tonumber(redis.call("get", stockKey)) <= 0) then
    --库存不足
    return 1;
end
--判断用户是否重复抢购
if (redis.call("sismember", orderKey, userId) == 1) then
    --用户重复抢购
    return 2;
end
--扣减库存
redis.call("incrby", stockKey, -1);
--记录用户抢购
redis.call("sadd", orderKey, userId);
return 0;
