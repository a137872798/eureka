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
 *   该对象就是将自身同步到 注册中心
 */
class InstanceInfoReplicator implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(InstanceInfoReplicator.class);

    /**
     * 维护了eurekaClient 实例对象
     */
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
     * 代表每分钟会消耗 多少令牌???
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

        // 代表每分钟生成一个令牌
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
            //这里为什么要用 原子引用来包裹 future 对象
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
     * 暂停本次任务并在之后重新执行
     * @return
     */
    public boolean onDemandUpdate() {
        // 尝试获取令牌 如果没有获取到的情况下就不进行更新
        if (rateLimiter.acquire(burstSize, allowedRatePerMinute)) {
            if (!scheduler.isShutdown()) {
                // 传入一个普通任务
                scheduler.submit(new Runnable() {
                    @Override
                    public void run() {
                        logger.debug("Executing on-demand update of local InstanceInfo");
    
                        Future latestPeriodic = scheduledPeriodicRef.get();
                        // 关闭本次任务
                        if (latestPeriodic != null && !latestPeriodic.isDone()) {
                            logger.debug("Canceling the latest scheduled update, it will be rescheduled at the end of on demand update");
                            latestPeriodic.cancel(false);
                        }

                        // 重新执行定时任务
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
            //刷新自身信息 一旦更新有效信息就将 instanceInfo 更新成dirty
            discoveryClient.refreshInstanceInfo();

            //获取 dirty 时间 首次启动得到时候 就会将自身设置为dirty  同时只有 dirtyTimestamp 不为空的时候 才执行register() 方法 难道不更新情况是不进行心跳的???
            Long dirtyTimestamp = instanceInfo.isDirtyWithTime();
            if (dirtyTimestamp != null) {
                //将自身注册到 eurekaServer 这里没有使用 register 的结果 也就是不在乎是否成功
                discoveryClient.register();
                //依据当前时间戳将 isDirty 修改成false
                instanceInfo.unsetIsDirty(dirtyTimestamp);
            }
        } catch (Throwable t) {
            logger.warn("There was a problem with the instance info replicator", t);
        } finally {
            //将下次任务 存入 定时器 等待执行
            Future next = scheduler.schedule(this, replicationIntervalSeconds, TimeUnit.SECONDS);
            //更新 future 对象 run 方法应该不会发生 竞争 为什么需要 原子更新
            scheduledPeriodicRef.set(next);
        }
    }

}
