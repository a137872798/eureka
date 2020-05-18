package com.netflix.discovery;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.util.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A task for updating and replicating the local instanceinfo to the remote server. Properties of this task are:
 * - configured with a single update thread to guarantee sequential update to the remote server
 * - update tasks can be scheduled on-demand via onDemandUpdate()
 * - task processing is rate limited by burstSize
 * - a new update task is always scheduled automatically after an earlier update task. However if an on-demand task
 *   is started, the scheduled automatic update task is discarded (and a new one will be scheduled after the new
 *   on-demand update).
 *
 *   @author dliu
 *
 *  基于AP的实现 client 需要定期将自己的信息同步到 eureka-server
 */
class InstanceInfoReplicator implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(InstanceInfoReplicator.class);


    private final DiscoveryClient discoveryClient;
    /**
     * 本机实例信息
     */
    private final InstanceInfo instanceInfo;

    /**
     * 该间隔应该就是每次将自身同步到 注册中心的时间间隔
     */
    private final int replicationIntervalSeconds;

    /**
     * 内部维护 定时器对象
     */
    private final ScheduledExecutorService scheduler;
    /**
     * 原子引用 保证并发的可见性和原子性
     */
    private final AtomicReference<Future> scheduledPeriodicRef;

    /**
     * 记录是否启动的标识
     */
    private final AtomicBoolean started;

    /**
     * 令牌桶算法的相关参数
     */
    private final RateLimiter rateLimiter;
    /**
     * 令牌桶大小
     */
    private final int burstSize;
    /**
     * 每分钟会产生多少令牌
     */
    private final int allowedRatePerMinute;

    /**
     * 创建 具备将自身信息 不断更新到注册中心的对象
     * @param discoveryClient
     * @param instanceInfo 本机作为 client的实例信息
     * @param replicationIntervalSeconds 将自身信息 发送到多注册中心的时间间隔
     * @param burstSize 令牌桶大小
     */
    InstanceInfoReplicator(DiscoveryClient discoveryClient, InstanceInfo instanceInfo, int replicationIntervalSeconds, int burstSize) {
        this.discoveryClient = discoveryClient;
        this.instanceInfo = instanceInfo;
        //初始化单线程定时器
        this.scheduler = Executors.newScheduledThreadPool(1,
                new ThreadFactoryBuilder()
                        .setNameFormat("DiscoveryClient-InstanceInfoReplicator-%d")
                        .setDaemon(true)
                        .build());

        this.scheduledPeriodicRef = new AtomicReference<Future>();

        this.started = new AtomicBoolean(false);

        // 生成限流桶对象
        this.rateLimiter = new RateLimiter(TimeUnit.MINUTES);
        this.replicationIntervalSeconds = replicationIntervalSeconds;
        this.burstSize = burstSize;
        this.allowedRatePerMinute = 60 * this.burstSize / this.replicationIntervalSeconds;
        logger.info("InstanceInfoReplicator onDemand update allowed rate per min is {}", allowedRatePerMinute);
    }

    /**
     * 启动任务
     * @param initialDelayMs
     */
    public void start(int initialDelayMs) {
        //注意 这里利用原子变量 来启动 这样可以避免并发启动出现的bug
        if (started.compareAndSet(false, true)) {
            // 把自身设置为dirty 就会触发下面的 register 逻辑 因为 只有是dirty的情况 才会进行重新注册(对应 instance信息发生变更的情况)
            instanceInfo.setIsDirty();  // for initial register
            //开始启动定时任务
            Future next = scheduler.schedule(this, initialDelayMs, TimeUnit.SECONDS);
            scheduledPeriodicRef.set(next);
        }
    }

    public void stop() {
        // 关闭 并等待任务终止
        shutdownAndAwaitTermination(scheduler);
        started.set(false);
    }

    /**
     * 关闭任务 并等待线程完成自身的清理任务
     * @param pool
     */
    private void shutdownAndAwaitTermination(ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.warn("InstanceInfoReplicator stop interrupted");
        }
    }

    /**
     * 当感知到本节点状态 相较eureka-server 上状态不同 则触发该方法
     * @return
     */
    public boolean onDemandUpdate() {
        // 避免该方法被频繁调用
        if (rateLimiter.acquire(burstSize, allowedRatePerMinute)) {
            if (!scheduler.isShutdown()) {
                // 传入一个普通任务
                scheduler.submit(new Runnable() {
                    @Override
                    public void run() {
                        logger.debug("Executing on-demand update of local InstanceInfo");

                        // 维护future的引用是为了可以提前关闭任务
                        Future latestPeriodic = scheduledPeriodicRef.get();
                        if (latestPeriodic != null && !latestPeriodic.isDone()) {
                            logger.debug("Canceling the latest scheduled update, it will be rescheduled at the end of on demand update");
                            latestPeriodic.cancel(false);
                        }

                        InstanceInfoReplicator.this.run();
                    }
                });
                return true;
            } else {
                logger.warn("Ignoring onDemand update due to stopped scheduler");
                return false;
            }
        } else {
            logger.warn("Ignoring onDemand update due to rate limiter");
            // 代表没有获得令牌 忽略本次更新
            return false;
        }
    }

    /**
     * 定时任务的执行逻辑 也就是定期将自身信息同步到注册中心
     */
    @Override
    public void run() {
        try {
            // 刷新当前实例信息
            discoveryClient.refreshInstanceInfo();

            // 代表实例发生了变化 那么就要将最新信息注册到eureka-server
            Long dirtyTimestamp = instanceInfo.isDirtyWithTime();
            if (dirtyTimestamp != null) {
                // 将最新信息注册到eureka-server
                discoveryClient.register();
                //依据当前时间戳将 isDirty 修改成false
                instanceInfo.unsetIsDirty(dirtyTimestamp);
            }
        } catch (Throwable t) {
            logger.warn("There was a problem with the instance info replicator", t);
        } finally {
            //将下次任务 存入 定时器 等待执行
            Future next = scheduler.schedule(this, replicationIntervalSeconds, TimeUnit.SECONDS);
            scheduledPeriodicRef.set(next);
        }
    }

}
