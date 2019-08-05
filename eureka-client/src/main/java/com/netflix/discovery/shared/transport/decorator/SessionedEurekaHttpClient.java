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

package com.netflix.discovery.shared.transport.decorator;

import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import com.netflix.discovery.shared.transport.EurekaHttpClient;
import com.netflix.discovery.shared.transport.EurekaHttpClientFactory;
import com.netflix.discovery.shared.transport.EurekaHttpResponse;
import com.netflix.discovery.shared.transport.TransportUtils;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.annotations.Monitor;
import com.netflix.servo.monitor.Monitors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.netflix.discovery.EurekaClientNames.METRIC_TRANSPORT_PREFIX;

/**
 * 在规定时间 会强制重连所有连接 防止  client 粘滞在一个 server上  也是实现装饰器模式
 * {@link SessionedEurekaHttpClient} enforces full reconnect at a regular interval (a session), preventing
 * a client to sticking to a particular Eureka server instance forever. This in turn guarantees even
 * load distribution in case of cluster topology change.
 *
 * @author Tomasz Bak
 */
public class SessionedEurekaHttpClient extends EurekaHttpClientDecorator {
    private static final Logger logger = LoggerFactory.getLogger(SessionedEurekaHttpClient.class);

    private final Random random = new Random();

    private final String name;
    private final EurekaHttpClientFactory clientFactory;
    private final long sessionDurationMs;
    private volatile long currentSessionDurationMs;

    private volatile long lastReconnectTimeStamp = -1;
    private final AtomicReference<EurekaHttpClient> eurekaHttpClientRef = new AtomicReference<>();

    /**
     * 构造函数
     * @param name
     * @param clientFactory
     * @param sessionDurationMs
     */
    public SessionedEurekaHttpClient(String name, EurekaHttpClientFactory clientFactory, long sessionDurationMs) {
        this.name = name;
        this.clientFactory = clientFactory;
        this.sessionDurationMs = sessionDurationMs;
        // 获取重建连接对象的时间
        this.currentSessionDurationMs = randomizeSessionDuration(sessionDurationMs);
        Monitors.registerObject(name, this);
    }

    /**
     * 通过该对象 来执行任务 实际的实现逻辑 在 request 对象内已经包含了
     * @param requestExecutor
     * @param <R>
     * @return
     */
    @Override
    protected <R> EurekaHttpResponse<R> execute(RequestExecutor<R> requestExecutor) {
        long now = System.currentTimeMillis();
        // 距离上次重连间隔时间
        long delay = now - lastReconnectTimeStamp;
        // 代表超过了 会话的维持时间
        if (delay >= currentSessionDurationMs) {
            logger.debug("Ending a session and starting anew");
            lastReconnectTimeStamp = now;
            // currentSessionDurationMs 在 sessionDurationMs 附近浮动
            currentSessionDurationMs = randomizeSessionDuration(sessionDurationMs);
            // 通过每次创建新的 client 来实现 reconnect
            TransportUtils.shutdown(eurekaHttpClientRef.getAndSet(null));
        }

        EurekaHttpClient eurekaHttpClient = eurekaHttpClientRef.get();
        if (eurekaHttpClient == null) {
            // 创建新对象
            eurekaHttpClient = TransportUtils.getOrSetAnotherClient(eurekaHttpClientRef, clientFactory.newClient());
        }
        // 委托执行
        return requestExecutor.execute(eurekaHttpClient);
    }

    @Override
    public void shutdown() {
        if(Monitors.isObjectRegistered(name, this)) {
            Monitors.unregisterObject(name, this);
        }
        TransportUtils.shutdown(eurekaHttpClientRef.getAndSet(null));
    }

    /**
     * @return a randomized sessionDuration in ms calculated as +/- an additional amount in [0, sessionDurationMs/2]
     * 生成一个 随机的 session 持续时间 应该是 按照这个时间来重建连接对象
     */
    protected long randomizeSessionDuration(long sessionDurationMs) {
        long delta = (long) (sessionDurationMs * (random.nextDouble() - 0.5));
        return sessionDurationMs + delta;
    }

    @Monitor(name = METRIC_TRANSPORT_PREFIX + "currentSessionDuration",
            description = "Duration of the current session", type = DataSourceType.GAUGE)
    public long getCurrentSessionDuration() {
        return lastReconnectTimeStamp < 0 ? 0 : System.currentTimeMillis() - lastReconnectTimeStamp;
    }
}
