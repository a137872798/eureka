/*
 * Copyright 2015 Netflix, Inc.
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

package com.netflix.eureka.util.batcher;

import com.netflix.eureka.util.batcher.TaskProcessor.ProcessingResult;

/**
 * {@link TrafficShaper} provides admission control policy prior to dispatching tasks to workers.
 * It reacts to events coming via reprocess requests (transient failures, congestion), and delays the processing
 * depending on this feedback.
 *
 * @author Tomasz Bak
 * 拥塞控制对象
 */
class TrafficShaper {

    /**
     * Upper bound on delay provided by configuration.
     * 延迟上限
     */
    private static final long MAX_DELAY = 30 * 1000;

    /**
     * 阻塞重试延迟
     */
    private final long congestionRetryDelayMs;
    /**
     * 网络错误重试延迟
     */
    private final long networkFailureRetryMs;

    // 发生错误时间
    private volatile long lastCongestionError;
    private volatile long lastNetworkFailure;

    /**
     * 使用指定的延迟时间来初始化
     * @param congestionRetryDelayMs
     * @param networkFailureRetryMs
     */
    TrafficShaper(long congestionRetryDelayMs, long networkFailureRetryMs) {
        this.congestionRetryDelayMs = Math.min(MAX_DELAY, congestionRetryDelayMs);
        this.networkFailureRetryMs = Math.min(MAX_DELAY, networkFailureRetryMs);
    }

    /**
     * 根据处理结果 记录错误时间
     * @param processingResult
     */
    void registerFailure(ProcessingResult processingResult) {
        if (processingResult == ProcessingResult.Congestion) {
            lastCongestionError = System.currentTimeMillis();
        } else if (processingResult == ProcessingResult.TransientError) {
            lastNetworkFailure = System.currentTimeMillis();
        }
    }

    /**
     * 获取传输延迟
     * @return
     */
    long transmissionDelay() {
        // 每次 获取过一次 延迟时间  就会将下面的某个属性重置为-1  也就是如果没有发生拥塞 这里就会返回0 不需要阻塞
        if (lastCongestionError == -1 && lastNetworkFailure == -1) {
            return 0;
        }

        // 发生了 哪种 异常就返回对应的延迟差值

        long now = System.currentTimeMillis();
        if (lastCongestionError != -1) {
            // 距离上次已经过了多少时间
            long congestionDelay = now - lastCongestionError;
            // 小于延迟时间时  返回差值 代表还需要延迟这么旧
            if (congestionDelay >= 0 && congestionDelay < congestionRetryDelayMs) {
                return congestionRetryDelayMs - congestionDelay;
            }
            lastCongestionError = -1;
        }

        if (lastNetworkFailure != -1) {
            long failureDelay = now - lastNetworkFailure;
            if (failureDelay >= 0 && failureDelay < networkFailureRetryMs) {
                return networkFailureRetryMs - failureDelay;
            }
            lastNetworkFailure = -1;
        }
        return 0;
    }
}
