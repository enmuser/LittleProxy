package org.littleshoot.proxy.impl;

/**
 * Configuration object for the proxy's thread pools. Controls the number of acceptor and worker threads in the Netty
 * {@link io.netty.channel.EventLoopGroup} used by the proxy.
 *
 * 代理的线程池的配置对象。
 * 控制代理使用的Netty {@link io.netty.channel.EventLoopGroup}中的接受方线程和工作线程数量。
 */
public class ThreadPoolConfiguration {
    private int acceptorThreads = ServerGroup.DEFAULT_INCOMING_ACCEPTOR_THREADS;
    private int clientToProxyWorkerThreads = ServerGroup.DEFAULT_INCOMING_WORKER_THREADS;
    private int proxyToServerWorkerThreads = ServerGroup.DEFAULT_OUTGOING_WORKER_THREADS;

    public int getClientToProxyWorkerThreads() {
        return clientToProxyWorkerThreads;
    }

    /**
     * Set the number of client-to-proxy worker threads to create. Worker threads perform the actual processing of
     * client requests. The default value is {@link ServerGroup#DEFAULT_INCOMING_WORKER_THREADS}.
     *
     * 设置要创建的客户端到代理工作线程的数量。工作线程执行客户请求的实际处理。
     * 默认值为{@link ServerGroup＃DEFAULT_INCOMING_WORKER_THREADS}。
     *
     * @param clientToProxyWorkerThreads number of client-to-proxy worker threads to create
     * @return this thread pool configuration instance, for chaining
     */
    public ThreadPoolConfiguration withClientToProxyWorkerThreads(int clientToProxyWorkerThreads) {
        this.clientToProxyWorkerThreads = clientToProxyWorkerThreads;
        return this;
    }

    public int getAcceptorThreads() {
        return acceptorThreads;
    }

    /**
     * Set the number of acceptor threads to create. Acceptor threads accept HTTP connections from the client and queue
     * them for processing by client-to-proxy worker threads. The default value is
     * {@link ServerGroup#DEFAULT_INCOMING_ACCEPTOR_THREADS}.
     *
     * 设置要创建的接收器线程数。接受器线程接受来自客户端的HTTP连接，并将它们排队由客户端到代理工作线程处理。
     * 默认值为{@link ServerGroup＃DEFAULT_INCOMING_ACCEPTOR_THREADS}
     *
     * @param acceptorThreads number of acceptor threads to create
     * @return this thread pool configuration instance, for chaining
     */
    public ThreadPoolConfiguration withAcceptorThreads(int acceptorThreads) {
        this.acceptorThreads = acceptorThreads;
        return this;
    }

    public int getProxyToServerWorkerThreads() {
        return proxyToServerWorkerThreads;
    }

    /**
     * Set the number of proxy-to-server worker threads to create. Proxy-to-server worker threads make requests to
     * upstream servers and process responses from the server. The default value is
     * {@link ServerGroup#DEFAULT_OUTGOING_WORKER_THREADS}.
     *
     * 设置要创建的代理到服务器工作线程的数量。代理到服务器工作线程向上游服务器发出请求，并处理来自服务器的响应。
     * 默认值为 {@link ServerGroup＃DEFAULT_OUTGOING_WORKER_THREADS}。
     *
     * @param proxyToServerWorkerThreads number of proxy-to-server worker threads to create
     * @return this thread pool configuration instance, for chaining
     */
    public ThreadPoolConfiguration withProxyToServerWorkerThreads(int proxyToServerWorkerThreads) {
        this.proxyToServerWorkerThreads = proxyToServerWorkerThreads;
        return this;
    }

}
