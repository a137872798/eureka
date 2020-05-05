/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.discovery.util;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rate limiter implementation is based on token bucket algorithm. There are two parameters:
 * <ul>
 * <li>
 *     burst size - maximum number of requests allowed into the system as a burst
 * </li>
 * <li>
 *     average rate - expected number of requests per second (RateLimiters using MINUTES is also supported)
 * </li>
 * </ul>
 *
 * @author Tomasz Bak
 * 令牌桶算法
 */
public class RateLimiter {

    /**
     * 这是一个换算用单位
     */
    private final long rateToMsConversion;

    /**
     * 被消耗的令牌数量
     */
    private final AtomicInteger consumedTokens = new AtomicInteger();
    /**
     * 上次被注满的时间
     */
    private final AtomicLong lastRefillTime = new AtomicLong(0);

    @Deprecated
    public RateLimiter() {
        this(TimeUnit.SECONDS);
    }

    /**
     * 通过时间单位对象来初始化令牌桶
     * @param averageRateUnit
     */
    public RateLimiter(TimeUnit averageRateUnit) {
        switch (averageRateUnit) {
            // 初始化换算单位
            case SECONDS:
                rateToMsConversion = 1000;
                break;
            case MINUTES:
                rateToMsConversion = 60 * 1000;
                break;
            default:
                throw new IllegalArgumentException("TimeUnit of " + averageRateUnit + " is not supported");
        }
    }

    /**
     * 传入 平均比率 和桶大小 来获取
     * @param burstSize
     * @param averageRate   每秒预期生成多少token
     * @return
     */
    public boolean acquire(int burstSize, long averageRate) {
        return acquire(burstSize, averageRate, System.currentTimeMillis());
    }

    /**
     *
     * @param burstSize 桶大小
     * @param averageRate 每秒生成多少token
     * @param currentTimeMillis 当前时间
     * @return
     */
    public boolean acquire(int burstSize, long averageRate, long currentTimeMillis) {
        // 替代抛出异常 选择直接放行
        if (burstSize <= 0 || averageRate <= 0) { // Instead of throwing exception, we just let all the traffic go
            return true;
        }

        // 一开始默认就可以获取 从 consumerBurst 到 burstSize 的 令牌数

        // 填满令牌  就是根据 距离上次 填充时间 乘上比率后生成 令牌数 再减去 当前已经使用的令牌数  burstSize - usedBurst == canUseToken
        refillToken(burstSize, averageRate, currentTimeMillis);
        // 消耗令牌
        return consumeToken(burstSize);
    }

    /**
     * 填满令牌
     * @param burstSize 桶大小
     * @param averageRate
     * @param currentTimeMillis 当前时间
     */
    private void refillToken(int burstSize, long averageRate, long currentTimeMillis) {
        // 获取上次填充满的时间
        long refillTime = lastRefillTime.get();
        // 代表距离上次填满过了多久
        long timeDelta = currentTimeMillis - refillTime;

        // 计算当前应当存在多少token
        long newTokens = timeDelta * averageRate / rateToMsConversion;
        // 代表 至少生成了 一个令牌
        if (newTokens > 0) {
            // 更新 新的填充时间
            long newRefillTime = refillTime == 0
                    ? currentTimeMillis
                    // 更新填充时间 注意这里是 按照 token数来计算 时间    newTokens * rateToMsConversion / averageRate   ==  timeDelta
                    : refillTime + newTokens * rateToMsConversion / averageRate;
            // 更新 填充令牌桶的时间 如果更新失败就代表被别人调用了就放弃本次获取令牌的动作
            if (lastRefillTime.compareAndSet(refillTime, newRefillTime)) {
                while (true) {
                    // 当前已经消耗了 多少令牌
                    int currentLevel = consumedTokens.get();
                    // 不能让 使用的令牌数 超过桶的大小
                    int adjustedLevel = Math.min(currentLevel, burstSize); // In case burstSize decreased
                    // 被消耗的减少了 也就是当前可用的变多了
                    int newLevel = (int) Math.max(0, adjustedLevel - newTokens);
                    if (consumedTokens.compareAndSet(currentLevel, newLevel)) {
                        return;
                    }
                }
            }
        }
    }

    /**
     * CAS 操作 使得 再并发场景下 正常获取令牌
     * @param burstSize
     * @return
     */
    private boolean consumeToken(int burstSize) {
        while (true) {
            // 当前已经消耗的令牌数  只要小于 桶的大小就可以不断尝试获取
            int currentLevel = consumedTokens.get();
            if (currentLevel >= burstSize) {
                return false;
            }
            if (consumedTokens.compareAndSet(currentLevel, currentLevel + 1)) {
                return true;
            }
        }
    }

    public void reset() {
        consumedTokens.set(0);
        lastRefillTime.set(0);
    }
}
