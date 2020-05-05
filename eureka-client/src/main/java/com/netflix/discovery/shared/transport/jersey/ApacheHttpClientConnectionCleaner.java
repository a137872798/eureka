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

package com.netflix.discovery.shared.transport.jersey;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.netflix.servo.monitor.BasicCounter;
import com.netflix.servo.monitor.BasicTimer;
import com.netflix.servo.monitor.Counter;
import com.netflix.servo.monitor.MonitorConfig;
import com.netflix.servo.monitor.Monitors;
import com.netflix.servo.monitor.Stopwatch;
import com.sun.jersey.client.apache4.ApacheHttpClient4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A periodic process running in background cleaning Apache http client connection pool out of idle connections.
 * This prevents from accumulating unused connections in half-closed state.
 * 该对象用于定时清理pool中空闲的连接
 */
public class ApacheHttpClientConnectionCleaner {

    private static final Logger logger = LoggerFactory.getLogger(ApacheHttpClientConnectionCleaner.class);

    /**
     * 清理间隔时间
     */
    private static final int HTTP_CONNECTION_CLEANER_INTERVAL_MS = 30 * 1000;

    private final ScheduledExecutorService eurekaConnCleaner =
            Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {

                private final AtomicInteger threadNumber = new AtomicInteger(1);

                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, "Apache-HttpClient-Conn-Cleaner" + threadNumber.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }
            });

    // 该对象好像是实际发送请求的 Client 的包装对象  赋予了池化的功能 清理空闲连接也是通过该对象
    private final ApacheHttpClient4 apacheHttpClient;

    // 下面2个对象是 netflix中用于统计的框架组件
    private final BasicTimer executionTimeStats;
    private final Counter cleanupFailed;

    public ApacheHttpClientConnectionCleaner(ApacheHttpClient4 apacheHttpClient, final long connectionIdleTimeout) {
        this.apacheHttpClient = apacheHttpClient;
        this.eurekaConnCleaner.scheduleWithFixedDelay(
                new Runnable() {
                    @Override
                    public void run() {
                        cleanIdle(connectionIdleTimeout);
                    }
                },
                HTTP_CONNECTION_CLEANER_INTERVAL_MS,
                HTTP_CONNECTION_CLEANER_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );

        MonitorConfig.Builder monitorConfigBuilder = MonitorConfig.builder("Eureka-Connection-Cleaner-Time");
        executionTimeStats = new BasicTimer(monitorConfigBuilder.build());
        cleanupFailed = new BasicCounter(MonitorConfig.builder("Eureka-Connection-Cleaner-Failure").build());
        try {
            Monitors.registerObject(this);
        } catch (Exception e) {
            logger.error("Unable to register with servo.", e);
        }
    }

    public void shutdown() {
        cleanIdle(0);
        eurekaConnCleaner.shutdown();
        Monitors.unregisterObject(this);
    }

    /**
     * 核心的定时清理任务
     * @param delayMs
     */
    public void cleanIdle(long delayMs) {
        Stopwatch start = executionTimeStats.start();
        try {
            apacheHttpClient.getClientHandler().getHttpClient()
                    .getConnectionManager()
                    .closeIdleConnections(delayMs, TimeUnit.SECONDS);
        } catch (Throwable e) {
            logger.error("Cannot clean connections", e);
            cleanupFailed.increment();
        } finally {
            if (null != start) {
                start.stop();
            }
        }
    }
}
