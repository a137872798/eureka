package com.netflix.eureka.util.batcher;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.netflix.eureka.util.batcher.TaskProcessor.ProcessingResult;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.annotations.Monitor;
import com.netflix.servo.monitor.MonitorConfig;
import com.netflix.servo.monitor.Monitors;
import com.netflix.servo.monitor.StatsTimer;
import com.netflix.servo.monitor.Timer;
import com.netflix.servo.stats.StatsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.netflix.eureka.Names.METRIC_REPLICATION_PREFIX;

/**
 * An active object with an internal thread accepting tasks from clients, and dispatching them to
 * workers in a pull based manner. Workers explicitly request an item or a batch of items whenever they are
 * available. This guarantees that data to be processed are always up to date, and no stale data processing is done.
 *
 * <h3>Task identification</h3>
 * Each task passed for processing has a corresponding task id. This id is used to remove duplicates (replace
 * older copies with newer ones).
 *
 * <h3>Re-processing</h3>
 * If data processing by a worker failed, and the failure is transient in nature, the worker will put back the
 * task(s) back to the {@link AcceptorExecutor}. This data will be merged with current workload, possibly discarded if
 * a newer version has been already received.
 *
 * @author Tomasz Bak
 * 任务处理器
 */
class AcceptorExecutor<ID, T> {

    private static final Logger logger = LoggerFactory.getLogger(AcceptorExecutor.class);

    private final String id;
    private final int maxBufferSize;
    private final int maxBatchingSize;
    private final long maxBatchingDelay;

    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    // 2个任务队列
    private final BlockingQueue<TaskHolder<ID, T>> acceptorQueue = new LinkedBlockingQueue<>();
    private final BlockingDeque<TaskHolder<ID, T>> reprocessQueue = new LinkedBlockingDeque<>();

    private final Thread acceptorThread;

    /**
     * 该容器携带将要执行的任务
     */
    private final Map<ID, TaskHolder<ID, T>> pendingTasks = new HashMap<>();
    /**
     * 该队列记录了任务插入的顺序 当isFull时 可以按照插入的顺序 将元素从map中移除
     */
    private final Deque<ID> processingOrder = new LinkedList<>();

    /**
     * 用于存储单项任务
     */
    private final BlockingQueue<TaskHolder<ID, T>> singleItemWorkQueue = new LinkedBlockingQueue<>();

    /**
     * 一开始用于控制并发读取的2个锁 凭证数都是0
     */
    private final Semaphore batchWorkRequests = new Semaphore(0);
    private final Semaphore singleItemWorkRequests = new Semaphore(0);

    /**
     * 该对象包含了多批消息
     */
    private final BlockingQueue<List<TaskHolder<ID, T>>> batchWorkQueue = new LinkedBlockingQueue<>();

    /**
     * 该对象会根据上次请求的结果 动态调整本次应当等待的时间
     */
    private final TrafficShaper trafficShaper;

    /*
     * Metrics
     */
    @Monitor(name = METRIC_REPLICATION_PREFIX + "acceptedTasks", description = "Number of accepted tasks", type = DataSourceType.COUNTER)
    volatile long acceptedTasks;

    @Monitor(name = METRIC_REPLICATION_PREFIX + "replayedTasks", description = "Number of replayedTasks tasks", type = DataSourceType.COUNTER)
    volatile long replayedTasks;

    @Monitor(name = METRIC_REPLICATION_PREFIX + "expiredTasks", description = "Number of expired tasks", type = DataSourceType.COUNTER)
    volatile long expiredTasks;

    @Monitor(name = METRIC_REPLICATION_PREFIX + "overriddenTasks", description = "Number of overridden tasks", type = DataSourceType.COUNTER)
    volatile long overriddenTasks;

    @Monitor(name = METRIC_REPLICATION_PREFIX + "queueOverflows", description = "Number of queue overflows", type = DataSourceType.COUNTER)
    volatile long queueOverflows;

    private final Timer batchSizeMetric;

    AcceptorExecutor(String id,
                     int maxBufferSize,
                     int maxBatchingSize,
                     long maxBatchingDelay,
                     long congestionRetryDelayMs,
                     long networkFailureRetryMs) {
        this.id = id;
        this.maxBufferSize = maxBufferSize;
        this.maxBatchingSize = maxBatchingSize;
        this.maxBatchingDelay = maxBatchingDelay;
        this.trafficShaper = new TrafficShaper(congestionRetryDelayMs, networkFailureRetryMs);

        // 这里创建了一个线程组 并将acceptorThread 归入该组
        ThreadGroup threadGroup = new ThreadGroup("eurekaTaskExecutors");
        this.acceptorThread = new Thread(threadGroup, new AcceptorRunner(), "TaskAcceptor-" + id);
        this.acceptorThread.setDaemon(true);
        this.acceptorThread.start();

        // 下面都是统计相关的
        final double[] percentiles = {50.0, 95.0, 99.0, 99.5};
        final StatsConfig statsConfig = new StatsConfig.Builder()
                .withSampleSize(1000)
                .withPercentiles(percentiles)
                .withPublishStdDev(true)
                .build();
        final MonitorConfig config = MonitorConfig.builder(METRIC_REPLICATION_PREFIX + "batchSize").build();
        this.batchSizeMetric = new StatsTimer(config, statsConfig);
        try {
            Monitors.registerObject(id, this);
        } catch (Throwable e) {
            logger.warn("Cannot register servo monitor for this object", e);
        }
    }

    // 使用该对象处理任务 实际上是添加到任务队列中  另一个工作线程会从这里拉取任务并处理
    void process(ID id, T task, long expiryTime) {
        acceptorQueue.add(new TaskHolder<ID, T>(id, task, expiryTime));
        acceptedTasks++;
    }

    // 当某次结果处理失败时 需要重新加入到队列
    void reprocess(List<TaskHolder<ID, T>> holders, ProcessingResult processingResult) {
        reprocessQueue.addAll(holders);
        replayedTasks += holders.size();
        // 同时修改拥塞对象内部的异常时间戳  便于生成下次请求的阻塞时间
        trafficShaper.registerFailure(processingResult);
    }

    // 针对单条任务处理结果
    void reprocess(TaskHolder<ID, T> taskHolder, ProcessingResult processingResult) {
        reprocessQueue.add(taskHolder);
        replayedTasks++;
        trafficShaper.registerFailure(processingResult);
    }

    /**
     * 当外部请求获取队列时 自动获取一个凭证
     * @return
     */
    BlockingQueue<TaskHolder<ID, T>> requestWorkItem() {
        singleItemWorkRequests.release();
        return singleItemWorkQueue;
    }

    BlockingQueue<List<TaskHolder<ID, T>>> requestWorkItems() {
        batchWorkRequests.release();
        return batchWorkQueue;
    }

    void shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            Monitors.unregisterObject(id, this);
            // 打断正在不断自旋读取数据的线程
            acceptorThread.interrupt();
        }
    }

    @Monitor(name = METRIC_REPLICATION_PREFIX + "acceptorQueueSize", description = "Number of tasks waiting in the acceptor queue", type = DataSourceType.GAUGE)
    public long getAcceptorQueueSize() {
        return acceptorQueue.size();
    }

    @Monitor(name = METRIC_REPLICATION_PREFIX + "reprocessQueueSize", description = "Number of tasks waiting in the reprocess queue", type = DataSourceType.GAUGE)
    public long getReprocessQueueSize() {
        return reprocessQueue.size();
    }

    @Monitor(name = METRIC_REPLICATION_PREFIX + "queueSize", description = "Task queue size", type = DataSourceType.GAUGE)
    public long getQueueSize() {
        return pendingTasks.size();
    }

    @Monitor(name = METRIC_REPLICATION_PREFIX + "pendingJobRequests", description = "Number of worker threads awaiting job assignment", type = DataSourceType.GAUGE)
    public long getPendingJobRequests() {
        return singleItemWorkRequests.availablePermits() + batchWorkRequests.availablePermits();
    }

    @Monitor(name = METRIC_REPLICATION_PREFIX + "availableJobs", description = "Number of jobs ready to be taken by the workers", type = DataSourceType.GAUGE)
    public long workerTaskQueueSize() {
        return singleItemWorkQueue.size() + batchWorkQueue.size();
    }

    class AcceptorRunner implements Runnable {
        @Override
        public void run() {
            long scheduleTime = 0;
            while (!isShutdown.get()) {
                try {
                    // 将任务移动到 待处理队列中
                    drainInputQueues();

                    // 原本被拉取到的所有任务数量 (因为可能超过单次处理的最大长度 pendingTask 与该队列的数量不同)
                    int totalItems = processingOrder.size();

                    long now = System.currentTimeMillis();
                    // 计算阻塞的解除时间戳
                    if (scheduleTime < now) {
                        scheduleTime = now + trafficShaper.transmissionDelay();
                    }
                    // 代表本次请求不会被阻塞
                    if (scheduleTime <= now) {
                        assignBatchWork();  // 将所有批任务移动到对应队列中
                        assignSingleItemWork();  // 一旦单条任务
                    }

                    // If no worker is requesting data or there is a delay injected by the traffic shaper,
                    // sleep for some time to avoid tight loop.
                    // 代表本次没有拉取到任何数据
                    if (totalItems == processingOrder.size()) {
                        Thread.sleep(10);
                    }
                } catch (InterruptedException ex) {
                    // Ignore
                } catch (Throwable e) {
                    // Safe-guard, so we never exit this loop in an uncontrolled way.
                    logger.warn("Discovery AcceptorThread error", e);
                }
            }
        }

        private boolean isFull() {
            return pendingTasks.size() >= maxBufferSize;
        }

        private void drainInputQueues() throws InterruptedException {
            do {
                // 将以下队列中的任务转移到 待处理队列中
                drainReprocessQueue();
                drainAcceptorQueue();

                if (!isShutdown.get()) {
                    // If all queues are empty, block for a while on the acceptor queue
                    if (reprocessQueue.isEmpty() && acceptorQueue.isEmpty() && pendingTasks.isEmpty()) {
                        TaskHolder<ID, T> taskHolder = acceptorQueue.poll(10, TimeUnit.MILLISECONDS);
                        if (taskHolder != null) {
                            appendTaskHolder(taskHolder);
                        }
                    }
                }
            } while (!reprocessQueue.isEmpty() || !acceptorQueue.isEmpty() || pendingTasks.isEmpty());
        }

        private void drainAcceptorQueue() {
            while (!acceptorQueue.isEmpty()) {
                appendTaskHolder(acceptorQueue.poll());
            }
        }

        private void drainReprocessQueue() {
            long now = System.currentTimeMillis();
            // isFull 代表 当前待处理任务是否达到上限
            while (!reprocessQueue.isEmpty() && !isFull()) {
                TaskHolder<ID, T> taskHolder = reprocessQueue.pollLast();
                ID id = taskHolder.getId();
                if (taskHolder.getExpiryTime() <= now) {
                    expiredTasks++;  // 过期任务将会被忽略
                } else if (pendingTasks.containsKey(id)) {
                    overriddenTasks++;   // 都是从后开始拉 能确保后面的任务比之前的新  这里相当于对id一致的任务进行去重
                } else {
                    pendingTasks.put(id, taskHolder);
                    // 加入到正在处理中的队列
                    processingOrder.addFirst(id);
                }
            }
            // 如果某次拉取任务时 任务已经堆满 那么剩下的将被忽略
            if (isFull()) {
                queueOverflows += reprocessQueue.size();
                reprocessQueue.clear();
            }
        }

        private void appendTaskHolder(TaskHolder<ID, T> taskHolder) {
            // isFull 代表的应该是单次处理的上限 满了的话 就打算抛弃本次待执行的任务
            if (isFull()) {
                pendingTasks.remove(processingOrder.poll());
                queueOverflows++;
            }
            TaskHolder<ID, T> previousTask = pendingTasks.put(taskHolder.getId(), taskHolder);
            if (previousTask == null) {
                processingOrder.add(taskHolder.getId());
            } else {
                overriddenTasks++;
            }
        }

        /**
         * 从任务队列中拉取单项任务
         */
        void assignSingleItemWork() {
            if (!processingOrder.isEmpty()) {
                if (singleItemWorkRequests.tryAcquire(1)) {
                    long now = System.currentTimeMillis();
                    while (!processingOrder.isEmpty()) {
                        ID id = processingOrder.poll();
                        TaskHolder<ID, T> holder = pendingTasks.remove(id);
                        if (holder.getExpiryTime() > now) {
                            singleItemWorkQueue.add(holder);
                            return;
                        }
                        expiredTasks++;
                    }
                    singleItemWorkRequests.release();
                }
            }
        }

        void assignBatchWork() {
            // 代表满足处理批任务的条件
            if (hasEnoughTasksForNextBatch()) {
                // 直接调用该方法 必然返回false 必须配合 requestWorkItems()（ 先通过该方法授予凭证 ）
                if (batchWorkRequests.tryAcquire(1)) {
                    long now = System.currentTimeMillis();
                    int len = Math.min(maxBatchingSize, processingOrder.size());
                    List<TaskHolder<ID, T>> holders = new ArrayList<>(len);
                    while (holders.size() < len && !processingOrder.isEmpty()) {
                        ID id = processingOrder.poll();
                        TaskHolder<ID, T> holder = pendingTasks.remove(id);
                        if (holder.getExpiryTime() > now) {
                            holders.add(holder);
                        } else {
                            expiredTasks++;
                        }
                    }
                    if (holders.isEmpty()) {
                        batchWorkRequests.release();
                    } else {
                        batchSizeMetric.record(holders.size(), TimeUnit.MILLISECONDS);
                        batchWorkQueue.add(holders);
                    }
                }
            }
        }

        /**
         * 2个维度 判断能否处理任务 一个是当前任务队列是否已满 还有一种就是判断任务的等待时间是否超过了 生成batch的时间
         * 有关batch的都是这个套路 包括kafka这种  要么数据积累到一定值 触发发送 要么等待时间满足一个 最大值 允许处理任务
         * @return
         */
        private boolean hasEnoughTasksForNextBatch() {
            if (processingOrder.isEmpty()) {
                return false;
            }
            if (pendingTasks.size() >= maxBufferSize) {
                return true;
            }

            TaskHolder<ID, T> nextHolder = pendingTasks.get(processingOrder.peek());
            long delay = System.currentTimeMillis() - nextHolder.getSubmitTimestamp();
            return delay >= maxBatchingDelay;
        }
    }
}
