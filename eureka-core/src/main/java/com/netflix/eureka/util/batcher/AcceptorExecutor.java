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
 * 抽象级 等同于 线程池
 */
class AcceptorExecutor<ID, T> {

    private static final Logger logger = LoggerFactory.getLogger(AcceptorExecutor.class);

    /**
     * 缓存队列最大长度  对应下面Map的 大小
     */
    private final int maxBufferSize;
    /**
     * 最多允许同一批的任务数量
     */
    private final int maxBatchingSize;
    /**
     * 延迟???
     */
    private final long maxBatchingDelay;

    /**
     * 当前线程池 是否停止
     */
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    /**
     * 接受任务的队列
     */
    private final BlockingQueue<TaskHolder<ID, T>> acceptorQueue = new LinkedBlockingQueue<>();
    /**
     * 重新处理任务的队列
     */
    private final BlockingDeque<TaskHolder<ID, T>> reprocessQueue = new LinkedBlockingDeque<>();
    /**
     * 接受任务的线程 难道也是 react模式  一个Boss 线程 和多个 Worker线程???
     */
    private final Thread acceptorThread;

    /**
     * 待处理的任务  优先级高于在 队列中的任务 一个 ID 下对应的 TaskHolder（维护了Task基本信息的对象）
     */
    private final Map<ID, TaskHolder<ID, T>> pendingTasks = new HashMap<>();
    /**
     * 该对象维护了 任务的处理顺序
     */
    private final Deque<ID> processingOrder = new LinkedList<>();

    /**
     * 信号量 就是一次允许多少个线程获得请求
     */
    private final Semaphore singleItemWorkRequests = new Semaphore(0);
    /**
     * 单项目工作队列
     */
    private final BlockingQueue<TaskHolder<ID, T>> singleItemWorkQueue = new LinkedBlockingQueue<>();

    /**
     * 批量工作请求
     */
    private final Semaphore batchWorkRequests = new Semaphore(0);
    /**
     * 批量工作队列
     */
    private final BlockingQueue<List<TaskHolder<ID, T>>> batchWorkQueue = new LinkedBlockingQueue<>();

    /**
     * 拥塞控制对象
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
        this.maxBufferSize = maxBufferSize;
        this.maxBatchingSize = maxBatchingSize;
        this.maxBatchingDelay = maxBatchingDelay;
        this.trafficShaper = new TrafficShaper(congestionRetryDelayMs, networkFailureRetryMs);

        // 创建线程组 代表是  eureka 的 Task 线程池
        ThreadGroup threadGroup = new ThreadGroup("eurekaTaskExecutors");
        // 创建 Boss线程
        this.acceptorThread = new Thread(threadGroup, new AcceptorRunner(), "TaskAcceptor-" + id);
        this.acceptorThread.setDaemon(true);
        this.acceptorThread.start();

        // 百分数 统计对象先不管
        final double[] percentiles = {50.0, 95.0, 99.0, 99.5};
        final StatsConfig statsConfig = new StatsConfig.Builder()
                .withSampleSize(1000)
                .withPercentiles(percentiles)
                .withPublishStdDev(true)
                .build();
        final MonitorConfig config = MonitorConfig.builder(METRIC_REPLICATION_PREFIX + "batchSize").build();
        this.batchSizeMetric = new StatsTimer(config, statsConfig);
        try {
            // 将该对象 注册到 monitor 中
            Monitors.registerObject(id, this);
        } catch (Throwable e) {
            logger.warn("Cannot register servo monitor for this object", e);
        }
    }

    /**
     * 处理任务
     * @param id
     * @param task
     * @param expiryTime
     */
    void process(ID id, T task, long expiryTime) {
        // 将任务存入到队列中
        acceptorQueue.add(new TaskHolder<ID, T>(id, task, expiryTime));
        acceptedTasks++;
    }

    /**
     * 重新执行某些任务
     * @param holders
     * @param processingResult  上次处理的结果
     */
    void reprocess(List<TaskHolder<ID, T>> holders, ProcessingResult processingResult) {
        reprocessQueue.addAll(holders);
        // 增加重新执行的任务数
        replayedTasks += holders.size();
        // 将异常结果设置到 阻塞控制对象上 这样之后 根据 延迟判断 就可以控制流量了
        trafficShaper.registerFailure(processingResult);
    }

    void reprocess(TaskHolder<ID, T> taskHolder, ProcessingResult processingResult) {
        reprocessQueue.add(taskHolder);
        replayedTasks++;
        trafficShaper.registerFailure(processingResult);
    }

    /**
     * 获取单任务队列
     * @return
     */
    BlockingQueue<TaskHolder<ID, T>> requestWorkItem() {
        // 给信号量 一个 获取锁的名额
        singleItemWorkRequests.release();
        return singleItemWorkQueue;
    }

    /**
     * 获取批量任务队列
     * @return
     */
    BlockingQueue<List<TaskHolder<ID, T>>> requestWorkItems() {
        batchWorkRequests.release();
        return batchWorkQueue;
    }

    void shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            // 给线程设置 被打断的标识
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

    /**
     * Boss 线程
     */
    class AcceptorRunner implements Runnable {
        @Override
        public void run() {
            long scheduleTime = 0;
            while (!isShutdown.get()) {
                try {
                    // 排除队列中 暂存的任务
                    drainInputQueues();

                    // 代表 当前map 中一共有多少任务待处理
                    int totalItems = processingOrder.size();

                    long now = System.currentTimeMillis();
                    if (scheduleTime < now) {
                        // 下次执行任务的时间
                        scheduleTime = now + trafficShaper.transmissionDelay();
                    }
                    // 代表没有被阻塞
                    if (scheduleTime <= now) {
                        // 立即执行 批量任务 和 单任务
                        assignBatchWork();
                        assignSingleItemWork();
                    }

                    // If no worker is requesting data or there is a delay injected by the traffic shaper,
                    // sleep for some time to avoid tight loop.
                    // 如果没有被 分派  短暂沉睡 进入下次循环  避免空转 可能出现的情况就是 之前任务失败了 所以要延时执行
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

        /**
         * 排出队列中等待处理的任务
         */
        private void drainInputQueues() throws InterruptedException {
            do {
                // 将 reprocess 中的任务 移动到待执行的区域
                drainReprocessQueue();
                // 将 accept中的任务移动到待执行区域
                drainAcceptorQueue();

                if (!isShutdown.get()) {
                    // If all queues are empty, block for a while on the acceptor queue
                    // 代表没有任务了
                    if (reprocessQueue.isEmpty() && acceptorQueue.isEmpty() && pendingTasks.isEmpty()) {
                        // 阻塞一段时间 内部应该是执行了一个 Condition
                        TaskHolder<ID, T> taskHolder = acceptorQueue.poll(10, TimeUnit.MILLISECONDS);
                        if (taskHolder != null) {
                            appendTaskHolder(taskHolder);
                        }
                    }
                }
            } while (!reprocessQueue.isEmpty() || !acceptorQueue.isEmpty() || pendingTasks.isEmpty());
        }

        /**
         * 从接受任务的队列中将任务转移到 待处理容器中
         */
        private void drainAcceptorQueue() {
            // 如果满了  这里还是会尝试 继续添加的 可能这些任务就会被抛弃
            while (!acceptorQueue.isEmpty()) {
                appendTaskHolder(acceptorQueue.poll());
            }
        }

        /**
         * 排出 尝试重试的任务
         */
        private void drainReprocessQueue() {
            long now = System.currentTimeMillis();
            // 必须先保证 Map 也就是 当前任务容器中没有满
            while (!reprocessQueue.isEmpty() && !isFull()) {
                // 弹出最后一个任务
                TaskHolder<ID, T> taskHolder = reprocessQueue.pollLast();
                ID id = taskHolder.getId();
                // 代表该任务已经过时
                if (taskHolder.getExpiryTime() <= now) {
                    expiredTasks++;
                    // 如果尝试执行的任务容器中已经存在该任务 就增加 被覆盖的任务数 (之前的任务应该是被覆盖了)
                } else if (pendingTasks.containsKey(id)) {
                    overriddenTasks++;
                } else {
                    // 将任务 重新加入 处理容器中
                    pendingTasks.put(id, taskHolder);
                    // 使用额外的 双端队列来 维护优先级 取任务就从这里获取 然后去 map中获取真正的任务对象 思路有点类似于 LinkedHashMap
                    // 这里会不断的加 直到 队列满了  Head 意味着这个任务的优先级是最低的 当必须处理的任务(acceptQueue) 中获取的无法设置到容器中时
                    // 就将 这个队列中的任务移除 （同时从map中移除关联的任务）
                    processingOrder.addFirst(id);
                }
            }
            if (isFull()) {
                // 增加超出的量
                queueOverflows += reprocessQueue.size();
                // 清除掉剩下的任务  这些 任务不执行了吗???
                reprocessQueue.clear();
            }
        }

        /**
         * 将任务添加 到 待处理容器
         * @param taskHolder
         */
        private void appendTaskHolder(TaskHolder<ID, T> taskHolder) {
            if (isFull()) {
                // 满了的话 就移除掉 最前面的任务 如果 acceptQueue 本身就大于 容器 那么注定有些任务无法被执行
                pendingTasks.remove(processingOrder.poll());
                // 这里应该是指 被抛弃的任务数
                queueOverflows++;
            }
            TaskHolder<ID, T> previousTask = pendingTasks.put(taskHolder.getId(), taskHolder);
            if (previousTask == null) {
                // 正常情况 会将该任务同时加入 优先级队列中 满了方便放弃  这里是将任务 放到 tail
                processingOrder.add(taskHolder.getId());
            } else {
                // 代表存在相同id 的任务 这个任务 替换了之前的任务
                overriddenTasks++;
            }
        }

        /**
         * 分配 单任务
         */
        void assignSingleItemWork() {
            // 代表还有 要处理的任务 也就是尺寸不能作为批处理 所以要在这里慢慢处理
            if (!processingOrder.isEmpty()) {
                //尝试获取锁 获取的到的情况 才会处理
                if (singleItemWorkRequests.tryAcquire(1)) {
                    long now = System.currentTimeMillis();
                    while (!processingOrder.isEmpty()) {
                        ID id = processingOrder.poll();
                        TaskHolder<ID, T> holder = pendingTasks.remove(id);
                        // 一次只取一个任务 同时没有释放锁 也就是 调用一次 request 释放一次锁 这里获取一个任务
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

        /**
         * 执行批量任务  这里应该是 任务 延迟过长 超过了允许的最大值 就将这些任务批量执行
         * 如果 数量不足 或者说延时没到 就走  分派单任务的逻辑
         */
        void assignBatchWork() {
            // 只有满足条件才会批量执行
            if (hasEnoughTasksForNextBatch()) {
                // 这里尝试获取锁 获取失败 不处理
                if (batchWorkRequests.tryAcquire(1)) {
                    long now = System.currentTimeMillis();
                    // 获取当前待处理的任务数量
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
                        // 这段什么意思???
                        batchWorkRequests.release();
                    } else {
                        // 记录当前时间
                        batchSizeMetric.record(holders.size(), TimeUnit.MILLISECONDS);
                        // 转移到 任务队列中
                        batchWorkQueue.add(holders);
                    }
                }
            }
        }

        /**
         * 代表要处理的任务 超过 本次容器大小  什么意思
         * @return
         */
        private boolean hasEnoughTasksForNextBatch() {
            if (processingOrder.isEmpty()) {
                return false;
            }
            // 一般应该只会出现 ==
            if (pendingTasks.size() >= maxBufferSize) {
                return true;
            }

            TaskHolder<ID, T> nextHolder = pendingTasks.get(processingOrder.peek());
            long delay = System.currentTimeMillis() - nextHolder.getSubmitTimestamp();
            return delay >= maxBatchingDelay;
        }
    }
}
