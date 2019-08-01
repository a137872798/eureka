package com.netflix.eureka.util.batcher;

/**
 * @author Tomasz Bak
 * 维护 Task 的 对象
 */
class TaskHolder<ID, T> {

    /**
     * 该task id
     */
    private final ID id;
    /**
     * task 对象
     */
    private final T task;
    /**
     * 任务过期时间
     */
    private final long expiryTime;
    /**
     * 过期时间
     */
    private final long submitTimestamp;

    TaskHolder(ID id, T task, long expiryTime) {
        this.id = id;
        this.expiryTime = expiryTime;
        this.task = task;
        this.submitTimestamp = System.currentTimeMillis();
    }

    public ID getId() {
        return id;
    }

    public T getTask() {
        return task;
    }

    public long getExpiryTime() {
        return expiryTime;
    }

    public long getSubmitTimestamp() {
        return submitTimestamp;
    }
}
