package com.netflix.appinfo;

/**
 * @author Nitesh Kant
 * 桥接对象 也可以看作是 适配器对象 将原来的 callback 桥接到 handler
 */
@SuppressWarnings("deprecation")
public class HealthCheckCallbackToHandlerBridge implements HealthCheckHandler {

    private final HealthCheckCallback callback;

    public HealthCheckCallbackToHandlerBridge() {
        callback = null;
    }

    public HealthCheckCallbackToHandlerBridge(HealthCheckCallback callback) {
        this.callback = callback;
    }

    @Override
    public InstanceInfo.InstanceStatus getStatus(InstanceInfo.InstanceStatus currentStatus) {
        // 如果是 start 或者 oos 不处理
        if (null == callback || InstanceInfo.InstanceStatus.STARTING == currentStatus
                || InstanceInfo.InstanceStatus.OUT_OF_SERVICE == currentStatus) { // Do not go to healthcheck handler if the status is starting or OOS.
            return currentStatus;
        }

        // 如果回调对象判断是健康就返回up 否则返回 down
        return callback.isHealthy() ? InstanceInfo.InstanceStatus.UP : InstanceInfo.InstanceStatus.DOWN;
    }
}
