-- Token bucket, evaluated atomically inside Redis.
--
-- Atomicity is the entire reason this is a script rather than a few commands: read, refill,
-- decide and write have to be one step, or two instances racing on the same key both see enough
-- tokens and both allow the request.
--
-- KEYS[1] bucket key
-- ARGV[1] capacity           tokens the bucket holds when full
-- ARGV[2] refillPerMilli     tokens added per millisecond
-- ARGV[3] nowMillis          caller's clock; passed in so the script stays deterministic
-- ARGV[4] cost               tokens this request consumes
--
-- returns { allowed (0|1), remaining, retryAfterMillis }

local capacity = tonumber(ARGV[1])
local refill   = tonumber(ARGV[2])
local now      = tonumber(ARGV[3])
local cost     = tonumber(ARGV[4])

local state  = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts     = tonumber(state[2])

if tokens == nil then
  tokens = capacity
  ts = now
end

-- Refill is continuous rather than a reset on a window boundary: a fixed window lets a caller
-- spend a full budget just before it rolls over and another immediately after, i.e. twice the
-- intended rate at the seam.
local elapsed = math.max(0, now - ts)
tokens = math.min(capacity, tokens + elapsed * refill)

local allowed = 0
local retry = 0
if tokens >= cost then
  tokens = tokens - cost
  allowed = 1
else
  retry = math.ceil((cost - tokens) / refill)
end

redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
-- Expire after the time it takes to refill completely. A full bucket is indistinguishable from
-- one that never existed, so keeping it past that point only leaks memory.
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / refill))

return { allowed, math.floor(tokens), retry }
