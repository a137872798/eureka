package com.netflix.eureka.cluster;

import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.shared.transport.EurekaHttpResponse;
import com.netflix.eureka.cluster.protocol.ReplicationInstance;
import com.netflix.eureka.cluster.protocol.ReplicationInstance.ReplicationInstanceBuilder;
import com.netflix.eureka.cluster.protocol.ReplicationInstanceResponse;
import com.netflix.eureka.cluster.protocol.ReplicationList;
import com.netflix.eureka.cluster.protocol.ReplicationListResponse;
import com.netflix.eureka.util.batcher.TaskProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.netflix.eureka.cluster.protocol.ReplicationInstance.ReplicationInstanceBuilder.aReplicationInstance;

/**
 * 重复任务处理对象
 * @author Tomasz Bak
 */
class ReplicationTaskProcessor implements TaskProcessor<ReplicationTask> {

    private static final Logger logger = LoggerFactory.getLogger(ReplicationTaskProcessor.class);

    /**
     * 发送重复任务请求的 client 对象
     */
    private final HttpReplicationClient replicationClient;

    /**
     * 同级节点的 id
     */
    private final String peerId;

    private volatile long lastNetworkErrorTime;
    
    private static final Pattern READ_TIME_OUT_PATTERN = Pattern.compile(".*read.*time.*out.*"); 

    ReplicationTaskProcessor(String peerId, HttpReplicationClient replicationClient) {
        this.replicationClient = replicationClient;
        this.peerId = peerId;
    }

    /**
     * 处理单任务
     * @param task
     * @return
     */
    @Override
    public ProcessingResult process(ReplicationTask task) {
        try {
            // 该人物对象本身就 封装了发送请求的逻辑
            EurekaHttpResponse<?> httpResponse = task.execute();
            int statusCode = httpResponse.getStatusCode();
            Object entity = httpResponse.getEntity();
            if (logger.isDebugEnabled()) {
                logger.debug("Replication task {} completed with status {}, (includes entity {})", task.getTaskName(), statusCode, entity != null);
            }
            // 成功情况下 处理对应逻辑
            if (isSuccess(statusCode)) {
                task.handleSuccess();
            } else if (statusCode == 503) {
                logger.debug("Server busy (503) reply for task {}", task.getTaskName());
                // 繁忙 之后会生成一个延迟
                return ProcessingResult.Congestion;
            } else {
                // 任务失败也会生成延迟
                task.handleFailure(statusCode, entity);
                return ProcessingResult.PermanentError;
            }
        } catch (Throwable e) {
        	if (maybeReadTimeOut(e)) {
                logger.error("It seems to be a socket read timeout exception, it will retry later. if it continues to happen and some eureka node occupied all the cpu time, you should set property 'eureka.server.peer-node-read-timeout-ms' to a bigger value", e);
            	//read timeout exception is more Congestion then TransientError, return Congestion for longer delay 
                return ProcessingResult.Congestion;
            } else if (isNetworkConnectException(e)) {
                logNetworkErrorSample(task, e);
                return ProcessingResult.TransientError;
            } else {
                logger.error("{}: {} Not re-trying this exception because it does not seem to be a network exception",
                        peerId, task.getTaskName(), e);
                return ProcessingResult.PermanentError;
            }
        }
        return ProcessingResult.Success;
    }

    /**
     * 处理批量任务
     * @param tasks
     * @return
     */
    @Override
    public ProcessingResult process(List<ReplicationTask> tasks) {
        ReplicationList list = createReplicationListOf(tasks);
        try {
            // 提交批量任务 返回批量结果
            EurekaHttpResponse<ReplicationListResponse> response = replicationClient.submitBatchUpdates(list);
            int statusCode = response.getStatusCode();
            if (!isSuccess(statusCode)) {
                if (statusCode == 503) {
                    logger.warn("Server busy (503) HTTP status code received from the peer {}; rescheduling tasks after delay", peerId);
                    return ProcessingResult.Congestion;
                } else {
                    // Unexpected error returned from the server. This should ideally never happen.
                    logger.error("Batch update failure with HTTP status code {}; discarding {} replication tasks", statusCode, tasks.size());
                    return ProcessingResult.PermanentError;
                }
            } else {
                // 处理批量结果
                handleBatchResponse(tasks, response.getEntity().getResponseList());
            }
        } catch (Throwable e) {
            if (maybeReadTimeOut(e)) {
                logger.error("It seems to be a socket read timeout exception, it will retry later. if it continues to happen and some eureka node occupied all the cpu time, you should set property 'eureka.server.peer-node-read-timeout-ms' to a bigger value", e);
            	//read timeout exception is more Congestion then TransientError, return Congestion for longer delay 
                return ProcessingResult.Congestion;
            } else if (isNetworkConnectException(e)) {
                logNetworkErrorSample(null, e);
                return ProcessingResult.TransientError;
            } else {
                logger.error("Not re-trying this exception because it does not seem to be a network exception", e);
                return ProcessingResult.PermanentError;
            }
        }
        return ProcessingResult.Success;
    }

    /**
     * We want to retry eagerly, but without flooding log file with tons of error entries.
     * As tasks are executed by a pool of threads the error logging multiplies. For example:
     * 20 threads * 100ms delay == 200 error entries / sec worst case
     * Still we would like to see the exception samples, so we print samples at regular intervals.
     */
    private void logNetworkErrorSample(ReplicationTask task, Throwable e) {
        long now = System.currentTimeMillis();
        if (now - lastNetworkErrorTime > 10000) {
            lastNetworkErrorTime = now;
            StringBuilder sb = new StringBuilder();
            sb.append("Network level connection to peer ").append(peerId);
            if (task != null) {
                sb.append(" for task ").append(task.getTaskName());
            }
            sb.append("; retrying after delay");
            logger.error(sb.toString(), e);
        }
    }

    private void handleBatchResponse(List<ReplicationTask> tasks, List<ReplicationInstanceResponse> responseList) {
        if (tasks.size() != responseList.size()) {
            // This should ideally never happen unless there is a bug in the software.
            logger.error("Batch response size different from submitted task list ({} != {}); skipping response analysis", responseList.size(), tasks.size());
            return;
        }
        for (int i = 0; i < tasks.size(); i++) {
            handleBatchResponse(tasks.get(i), responseList.get(i));
        }
    }

    /**
     * 根据code 确定 执行success 还是 failure
     * @param task
     * @param response
     */
    private void handleBatchResponse(ReplicationTask task, ReplicationInstanceResponse response) {
        int statusCode = response.getStatusCode();
        if (isSuccess(statusCode)) {
            task.handleSuccess();
            return;
        }

        try {
            task.handleFailure(response.getStatusCode(), response.getResponseEntity());
        } catch (Throwable e) {
            logger.error("Replication task {} error handler failure", task.getTaskName(), e);
        }
    }

    /**
     * 将 多个重复任务 整合起来
     * @param tasks
     * @return
     */
    private ReplicationList createReplicationListOf(List<ReplicationTask> tasks) {
        ReplicationList list = new ReplicationList();
        for (ReplicationTask task : tasks) {
            // Only InstanceReplicationTask are batched.
            list.addReplicationInstance(createReplicationInstanceOf((InstanceReplicationTask) task));
        }
        return list;
    }

    private static boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    /**
     * Check if the exception is some sort of network timeout exception (ie)
     * read,connect.
     *
     * @param e
     *            The exception for which the information needs to be found.
     * @return true, if it is a network timeout, false otherwise.
     */
    private static boolean isNetworkConnectException(Throwable e) {
        do {
            if (IOException.class.isInstance(e)) {
                return true;
            }
            e = e.getCause();
        } while (e != null);
        return false;
    }
    
    /**
     * Check if the exception is socket read time out exception
     *
     * @param e
     *            The exception for which the information needs to be found.
     * @return true, if it may be a socket read time out exception.
     */
    private static boolean maybeReadTimeOut(Throwable e) {
        do {
            if (IOException.class.isInstance(e)) {
            	String message = e.getMessage().toLowerCase();
            	Matcher matcher = READ_TIME_OUT_PATTERN.matcher(message);
            	if(matcher.find()) {
            		return true;
            	}
            }
            e = e.getCause();
        } while (e != null);
        return false;
    }

    /**
     * 将task 对象中基本信息抽取出来生成 instance对象
     * @param task
     * @return
     */
    private static ReplicationInstance createReplicationInstanceOf(InstanceReplicationTask task) {
        ReplicationInstanceBuilder instanceBuilder = aReplicationInstance();
        instanceBuilder.withAppName(task.getAppName());
        instanceBuilder.withId(task.getId());
        InstanceInfo instanceInfo = task.getInstanceInfo();
        if (instanceInfo != null) {
            String overriddenStatus = task.getOverriddenStatus() == null ? null : task.getOverriddenStatus().name();
            instanceBuilder.withOverriddenStatus(overriddenStatus);
            instanceBuilder.withLastDirtyTimestamp(instanceInfo.getLastDirtyTimestamp());
            if (task.shouldReplicateInstanceInfo()) {
                instanceBuilder.withInstanceInfo(instanceInfo);
            }
            String instanceStatus = instanceInfo.getStatus() == null ? null : instanceInfo.getStatus().name();
            instanceBuilder.withStatus(instanceStatus);
        }
        instanceBuilder.withAction(task.getAction());
        return instanceBuilder.build();
    }
}

