package org.littleshoot.proxy.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A ThreadFactory that adds LittleProxy-specific information to the threads' names.
 * 一个ThreadFactory，它将LittleProxy特定的信息添加到线程的名称中
 */
public class CategorizedThreadFactory implements ThreadFactory {
    private static final Logger log = LoggerFactory.getLogger(CategorizedThreadFactory.class);

    private final String name;
    private final String category;
    private final int uniqueServerGroupId;

    private AtomicInteger threadCount = new AtomicInteger(0);

    /**
     * Exception handler for proxy threads. Logs the name of the thread and the exception that was caught.
     * 代理线程的异常处理程序。记录线程的名称和捕获的异常
     */
    private static final Thread.UncaughtExceptionHandler UNCAUGHT_EXCEPTION_HANDLER = new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
            log.error("Uncaught throwable in thread: {}", t.getName(), e);
        }
    };


    /**
     * @param name the user-supplied name of this proxy
     * @param category the type of threads this factory is creating (acceptor, client-to-proxy worker, proxy-to-server worker)
     * @param uniqueServerGroupId a unique number for the server group creating this thread factory, to differentiate multiple proxy instances with the same name
     *
     *  @param //名称此代理的用户提供的名称
     *  @param //类别此工厂正在创建的线程类型（接受者，客户端到代理工作人员，代理到服务器工作人员）
     *  @param uniqueServerGroupId 服务器的唯一编号组创建此线程工厂，以区分具有相同名称的多个代理实例
     *
     */
    public CategorizedThreadFactory(String name, String category, int uniqueServerGroupId) {
        this.category = category;
        this.name = name;
        this.uniqueServerGroupId = uniqueServerGroupId;
    }

    @Override
    public Thread newThread(Runnable r) {
        Thread t = new Thread(r, name + "-" + uniqueServerGroupId + "-" + category + "-" + threadCount.getAndIncrement());

        t.setUncaughtExceptionHandler(UNCAUGHT_EXCEPTION_HANDLER);

        return t;
    }

}
