package com.netflix.discovery.shared.resolver;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.netflix.discovery.TimedSupervisorTask;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.annotations.Monitor;
import com.netflix.servo.monitor.Monitors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.netflix.discovery.EurekaClientNames.METRIC_RESOLVER_PREFIX;

/**
 * An async resolver that keeps a cached version of the endpoint list value for gets, and updates this cache
 * periodically in a different thread.
 * 异步解析器
 *
 * @author David Liu
 */
public class AsyncResolver<T extends EurekaEndpoint> implements ClosableResolver<T> {
    private static final Logger logger = LoggerFactory.getLogger(AsyncResolver.class);

    // Note that warm up is best effort. If the resolver is accessed by multiple threads pre warmup,
    // only the first thread will block for the warmup (up to the configurable timeout).
    /**
     * 是否已经开始预热
     */
    private final AtomicBoolean warmedUp = new AtomicBoolean(false);
    /**
     * 是否开始计时
     */
    private final AtomicBoolean scheduled = new AtomicBoolean(false);

    private final String name;    // a name for metric purposes
    /**
     * 内部的集群解析器代理对象
     */
    private final ClusterResolver<T> delegate;
    private final ScheduledExecutorService executorService;
    private final ThreadPoolExecutor threadPoolExecutor;
    /**
     * 具备统计功能的task
     */
    private final TimedSupervisorTask backgroundTask;
    private final AtomicReference<List<T>> resultsRef;

    /**
     * 刷新时间间隔
     */
    private final int refreshIntervalMs;
    /**
     * 预热超时时间
     */
    private final int warmUpTimeoutMs;

    // Metric timestamp, tracking last time when data were effectively changed.
    private volatile long lastLoadTimestamp = -1;

    /**
     * Create an async resolver with an empty initial value. When this resolver is called for the first time,
     * an initial warm up will be executed before scheduling the periodic update task.
     */
    public AsyncResolver(String name,
                         ClusterResolver<T> delegate,
                         int executorThreadPoolSize,
                         int refreshIntervalMs,
                         int warmUpTimeoutMs) {
        this(
                name,
                delegate,
                Collections.<T>emptyList(),
                executorThreadPoolSize,
                refreshIntervalMs,
                warmUpTimeoutMs
        );
    }

    /**
     * Create an async resolver with a preset initial value. When this resolver is called for the first time,
     * there will be no warm up and the initial value will be returned. The periodic update task will not be
     * scheduled until after the first time getClusterEndpoints call.
     * 创建异步解析器  initialValues 代表可选的 eurekaServer 列表
     */
    public AsyncResolver(String name,
                         ClusterResolver<T> delegate,
                         List<T> initialValues,
                         int executorThreadPoolSize,
                         int refreshIntervalMs) {
        this(
                name,
                delegate,
                initialValues,
                executorThreadPoolSize,
                refreshIntervalMs,
                0
        );

        // 代表开始预热
        warmedUp.set(true);
    }

    /**
     * @param delegate the delegate resolver to async resolve from
     * @param initialValue the initial value to use
     * @param executorThreadPoolSize the max number of threads for the threadpool
     * @param refreshIntervalMs the async refresh interval
     * @param warmUpTimeoutMs the time to wait for the initial warm up
     */
    AsyncResolver(String name,
                  ClusterResolver<T> delegate,
                  List<T> initialValue,
                  int executorThreadPoolSize,
                  int refreshIntervalMs,
                  int warmUpTimeoutMs) {
        this.name = name;
        this.delegate = delegate;
        this.refreshIntervalMs = refreshIntervalMs;
        this.warmUpTimeoutMs = warmUpTimeoutMs;

        this.executorService = Executors.newScheduledThreadPool(1,
                new ThreadFactoryBuilder()
                        .setNameFormat("AsyncResolver-" + name + "-%d")
                        .setDaemon(true)
                        .build());

        this.threadPoolExecutor = new ThreadPoolExecutor(
                1, executorThreadPoolSize, 0, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(),  // use direct handoff
                new ThreadFactoryBuilder()
                        .setNameFormat("AsyncResolver-" + name + "-executor-%d")
                        .setDaemon(true)
                        .build()
        );

        // 这里把线程池对象带入到 Task中
        this.backgroundTask = new TimedSupervisorTask(
                this.getClass().getSimpleName(),
                executorService,
                threadPoolExecutor,
                refreshIntervalMs,
                TimeUnit.MILLISECONDS,
                5,
                updateTask
        );

        // 看来resultsRef 存放的是注册中心列表  这里先设置一个初始化 并开启一个后台线程定时拉取endpoint 针对从config中获取的情况endpoint是不变的
        this.resultsRef = new AtomicReference<>(initialValue);
        Monitors.registerObject(name, this);
    }

    /**
     * 关闭线程池 和 后台任务
     */
    @Override
    public void shutdown() {
        if(Monitors.isObjectRegistered(name, this)) {
            Monitors.unregisterObject(name, this);
        }
        executorService.shutdownNow();
        threadPoolExecutor.shutdownNow();
        backgroundTask.cancel();
    }


    @Override
    public String getRegion() {
        return delegate.getRegion();
    }

    /**
     * 针对该对象 执行获取 注册中心 Endpoint 是异步操作
     * @return
     */
    @Override
    public List<T> getClusterEndpoints() {
        // 在初始化时 warmedUp 应该已经修改成 true 了
        long delay = refreshIntervalMs;
        if (warmedUp.compareAndSet(false, true)) {
            if (!doWarmUp()) {
                delay = 0;
            }
        }
        if (scheduled.compareAndSet(false, true)) {
            scheduleTask(delay);
        }
        return resultsRef.get();
    }


    /* visible for testing */ boolean doWarmUp() {
        Future future = null;
        try {
            future = threadPoolExecutor.submit(updateTask);
            future.get(warmUpTimeoutMs, TimeUnit.MILLISECONDS);  // block until done or timeout
            return true;
        } catch (Exception e) {
            logger.warn("Best effort warm up failed", e);
        } finally {
            if (future != null) {
                future.cancel(true);
            }
        }
        return false;
    }

    /* visible for testing */ void scheduleTask(long delay) {
        executorService.schedule(
                backgroundTask, delay, TimeUnit.MILLISECONDS);
    }

    @Monitor(name = METRIC_RESOLVER_PREFIX + "lastLoadTimestamp",
            description = "How much time has passed from last successful async load", type = DataSourceType.GAUGE)
    public long getLastLoadTimestamp() {
        return lastLoadTimestamp < 0 ? 0 : System.currentTimeMillis() - lastLoadTimestamp;
    }

    @Monitor(name = METRIC_RESOLVER_PREFIX + "endpointsSize",
            description = "How many records are the in the endpoints ref", type = DataSourceType.GAUGE)
    public long getEndpointsSize() {
        return resultsRef.get().size();  // return directly from the ref and not the method so as to not trigger warming
    }

    /**
     * 在后台执行获取 endpoint 的任务
     */
    private final Runnable updateTask = new Runnable() {
        @Override
        public void run() {
            try {
                List<T> newList = delegate.getClusterEndpoints();
                if (newList != null) {
                    resultsRef.getAndSet(newList);
                    lastLoadTimestamp = System.currentTimeMillis();
                } else {
                    logger.warn("Delegate returned null list of cluster endpoints");
                }
                logger.debug("Resolved to {}", newList);
            } catch (Exception e) {
                logger.warn("Failed to retrieve cluster endpoints from the delegate", e);
            }
        }
    };
}
