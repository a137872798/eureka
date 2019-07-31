package com.netflix.eureka;

import com.netflix.discovery.converters.JsonXStream;
import com.netflix.discovery.converters.XmlXStream;
import com.netflix.eureka.util.EurekaMonitors;
import com.netflix.governator.LifecycleInjector;
import com.netflix.governator.guice.servlet.GovernatorServletContextListener;
import com.thoughtworks.xstream.XStream;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;

/**
 * @author David
 * 上下文监听器对象 该对象的父类是  servleteContextListener 会监听servlet 相关的 生命周期事件
 * 该对象应该是 监听 servlet 的事件后 初始化 内部维护的 EurekaServerContext
 */
public class EurekaContextListener extends GovernatorServletContextListener {

    /**
     * 该对象维护的  Context
     */
    private EurekaServerContext serverContext;

    /**
     * 针对初始化的事件
     * @param servletContextEvent
     */
    @Override
    public void contextInitialized(ServletContextEvent servletContextEvent) {
        // 上层的先不管 好像是调用 Inject 相关的代码
        super.contextInitialized(servletContextEvent);

        // 获取 servletContext 对象
        ServletContext sc = servletContextEvent.getServletContext();
        // 为 servletContext设置 eurekaContext 对象
        sc.setAttribute(EurekaServerContext.class.getName(), serverContext);

        // Copy registry from neighboring eureka node
        int registryCount = serverContext.getRegistry().syncUp();
        serverContext.getRegistry().openForTraffic(serverContext.getApplicationInfoManager(), registryCount);

        // Register all monitoring statistics.
        EurekaMonitors.registerAllStats();
    }

    public void contextDestroyed(ServletContextEvent servletContextEvent) {
        EurekaMonitors.shutdown();

        ServletContext sc = servletContextEvent.getServletContext();
        sc.removeAttribute(EurekaServerContext.class.getName());
        super.contextDestroyed(servletContextEvent);
    }

    @Override
    protected LifecycleInjector createInjector() {
        // For backward compatibility
        JsonXStream.getInstance().registerConverter(new V1AwareInstanceInfoConverter(), XStream.PRIORITY_VERY_HIGH);
        XmlXStream.getInstance().registerConverter(new V1AwareInstanceInfoConverter(), XStream.PRIORITY_VERY_HIGH);

        LifecycleInjector injector = EurekaInjectorCreator.createInjector();
        serverContext = injector.getInstance(EurekaServerContext.class);
        return injector;
    }
}
