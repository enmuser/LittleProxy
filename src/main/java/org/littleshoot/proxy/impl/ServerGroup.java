package org.littleshoot.proxy.impl;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.udt.nio.NioUdtProvider;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.TransportProtocol;
import org.littleshoot.proxy.UnknownTransportProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages thread pools for one or more proxy server instances. When servers are created, they must register with the
 * ServerGroup using {@link #registerProxyServer(HttpProxyServer)}, and when they shut down, must unregister with the
 * ServerGroup using {@link #unregisterProxyServer(HttpProxyServer, boolean)}.
 *
 * 管理一个或多个代理服务器实例的线程池。创建服务器时，它们必须使用{@link #registerProxyServer（HttpProxyServer）}向ServerGroup注册，
 * 关闭服务器时，必须使用{@link #unregisterProxyServer（HttpProxyServer，boolean）}向ServerGroup取消注册。
 */
public class ServerGroup {
    private static final Logger log = LoggerFactory.getLogger(ServerGroup.class);

    /**
     * The default number of threads to accept incoming requests from clients. (Requests are serviced by worker threads,
     * not acceptor threads.)
     * 接受来自客户端的传入请求的默认线程数。（请求由工作线程服务，不是接受者线程。）
     */
    public static final int DEFAULT_INCOMING_ACCEPTOR_THREADS = 2;

    /**
     * The default number of threads to service incoming requests from clients.
     * 服务来自客户端的传入请求的默认线程数
     */
    public static final int DEFAULT_INCOMING_WORKER_THREADS = 8;

    /**
     * The default number of threads to service outgoing requests to servers.
     * 服务到服务器的传出请求的默认线程数
     */
    public static final int DEFAULT_OUTGOING_WORKER_THREADS = 8;

    /**
     * Global counter for the {@link #serverGroupId}.
     * {@link #serverGroupId}的全局计数器。
     */
    private static final AtomicInteger serverGroupCount = new AtomicInteger(0);

    /**
     * A name for this ServerGroup to use in naming threads.
     * 此ServerGroup在命名线程中使用的名称
     */
    private final String name;

    /**
     * The ID of this server group. Forms part of the name of each thread created for this server group. Useful for
     * differentiating threads when multiple proxy instances are running.
     * 该服务器组的ID。构成为此服务器组创建的每个线程的名称的一部分。当多个代理实例正在运行时，有助于区分线程。
      */
    private final int serverGroupId;

    private final int incomingAcceptorThreads;
    private final int incomingWorkerThreads;
    private final int outgoingWorkerThreads;

    /**
     * List of all servers registered to use this ServerGroup. Any access to this list should be synchronized using the
     * {@link #SERVER_REGISTRATION_LOCK}.
     * 已注册使用此ServerGroup的所有服务器的列表。对此列表的任何访问都应使用{@link #SERVER_REGISTRATION_LOCK}.
     */
    public final List<HttpProxyServer> registeredServers = new ArrayList<HttpProxyServer>(1);

    /**
     * A mapping of {@link TransportProtocol}s to their initialized {@link ProxyThreadPools}. Each transport uses a
     * different thread pool, since the initialization parameters are different.
     * {@link TransportProtocol}到其初始化的{@link ProxyThreadPools}的映射。
     * 每个传输使用一个不同的线程池，因为初始化参数不同
     */
    private final EnumMap<TransportProtocol, ProxyThreadPools> protocolThreadPools = new EnumMap<TransportProtocol, ProxyThreadPools>(TransportProtocol.class);

    /**
     * A mapping of selector providers to transport protocols. Avoids special-casing each transport protocol during
     * transport protocol initialization.
     * 选择器提供程序到传输协议的映射。避免在传输协议初始化期间对每个传输协议进行特殊包装
     */
    private static final EnumMap<TransportProtocol, SelectorProvider> TRANSPORT_PROTOCOL_SELECTOR_PROVIDERS = new EnumMap<TransportProtocol, SelectorProvider>(TransportProtocol.class);
    static {
        TRANSPORT_PROTOCOL_SELECTOR_PROVIDERS.put(TransportProtocol.TCP, SelectorProvider.provider());

        // allow the proxy to operate without UDT support. this allows clients that do not use UDT to exclude the barchart
        // dependency completely.

        /*
         *允许代理在没有UDT支持的情况下运行。这允许不使用UDT的客户端完全排除barchart依赖关系
         */
        if (ProxyUtils.isUdtAvailable()) {
            TRANSPORT_PROTOCOL_SELECTOR_PROVIDERS.put(TransportProtocol.UDT, NioUdtProvider.BYTE_PROVIDER);
        } else {
            log.debug("UDT provider not found on classpath. UDT transport will not be available.");
        }
    }

    /**
     * True when this ServerGroup is stopped.
     * 当此ServerGroup停止时为true。
     */
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /**
     * Creates a new ServerGroup instance for a proxy. Threads created for this ServerGroup will have the specified
     * ServerGroup name in the Thread name. This constructor does not actually initialize any thread pools; instead,
     * thread pools for specific transport protocols are lazily initialized as needed.
     *
     * 为代理创建一个新的ServerGroup实例。
     * 为此ServerGroup创建的线程将在线程名称中具有指定的ServerGroup名称。
     * 该构造函数实际上并不初始化任何线程池；而是，根据需要延迟初始化用于特定传输协议的线程池
     *
     * @param name ServerGroup name to include in thread names
     * @param incomingAcceptorThreads number of acceptor threads per protocol
     * @param incomingWorkerThreads number of client-to-proxy worker threads per protocol
     * @param outgoingWorkerThreads number of proxy-to-server worker threads per protocol
     */
    public ServerGroup(String name, int incomingAcceptorThreads, int incomingWorkerThreads, int outgoingWorkerThreads) {
        this.name = name;
        this.serverGroupId = serverGroupCount.getAndIncrement();
        this.incomingAcceptorThreads = incomingAcceptorThreads;
        this.incomingWorkerThreads = incomingWorkerThreads;
        this.outgoingWorkerThreads = outgoingWorkerThreads;
    }

    /**
     * Lock for initializing any transport protocols.
     * 锁定以初始化任何传输协议
     */
    private final Object THREAD_POOL_INIT_LOCK = new Object();

    /**
     * Retrieves the {@link ProxyThreadPools} for the specified transport protocol. Lazily initializes the thread pools
     * for the transport protocol if they have not yet been initialized. If the protocol has already been initialized,
     * this method returns immediately, without synchronization. If initialization is necessary, the initialization
     * process creates the acceptor and worker threads necessary to service requests to/from the proxy.
     * 检索指定传输协议的{@link ProxyThreadPools}。如果尚未初始化传输协议的线程池，请懒惰地对其进行初始化。
     * 如果协议已经初始化，则此方法立即返回，而不会同步。
     * 如果必须进行初始化，则初始化进程将创建服务于往返于代理的请求所必需的接收器线程和工作线程
     * <p>
     * This method is thread-safe; no external locking is necessary.
     * 该方法是线程安全的；无需外部锁定
     *
     * @param protocol transport protocol to retrieve thread pools for
     * @return thread pools for the specified transport protocol
     */
    private ProxyThreadPools getThreadPoolsForProtocol(TransportProtocol protocol) {
        // if the thread pools have not been initialized for this protocol, initialize them
        if (protocolThreadPools.get(protocol) == null) {
            synchronized (THREAD_POOL_INIT_LOCK) {
                if (protocolThreadPools.get(protocol) == null) {
                    log.debug("Initializing thread pools for {} with {} acceptor threads, {} incoming worker threads, and {} outgoing worker threads",
                            protocol, incomingAcceptorThreads, incomingWorkerThreads, outgoingWorkerThreads);

                    SelectorProvider selectorProvider = TRANSPORT_PROTOCOL_SELECTOR_PROVIDERS.get(protocol);
                    if (selectorProvider == null) {
                        throw new UnknownTransportProtocolException(protocol);
                    }

                    ProxyThreadPools threadPools = new ProxyThreadPools(selectorProvider,
                            incomingAcceptorThreads,
                            incomingWorkerThreads,
                            outgoingWorkerThreads,
                            name,
                            serverGroupId);
                    protocolThreadPools.put(protocol, threadPools);
                }
            }
        }

        return protocolThreadPools.get(protocol);
    }

    /**
     * Lock controlling access to the {@link #registerProxyServer(HttpProxyServer)} and {@link #unregisterProxyServer(HttpProxyServer, boolean)}
     * methods.
     *
     * 锁定控制对{@link #registerProxyServer（HttpProxyServer）}
     * 和{@link #unregisterProxyServer（HttpProxyServer，boolean）}方法的访问。
     */
    private final Object SERVER_REGISTRATION_LOCK = new Object();

    /**
     * Registers the specified proxy server as a consumer of this server group. The server group will not be shut down
     * until the proxy unregisters itself.
     *
     * 将指定的代理服务器注册为该服务器组的使用者。服务器组将不会关闭直到代理自行注销。
     *
     * @param proxyServer proxy server instance to register
     */
    public void registerProxyServer(HttpProxyServer proxyServer) {
        synchronized (SERVER_REGISTRATION_LOCK) {
            registeredServers.add(proxyServer);
        }
    }

    /**
     * Unregisters the specified proxy server from this server group. If this was the last registered proxy server, the
     * server group will be shut down.
     *
     * 从该服务器组中注销指定的代理服务器。如果这是最后注册的代理服务器，则服务器组将被关闭。
     *
     * @param proxyServer proxy server instance to unregister
     * @param graceful when true, the server group shutdown (if necessary) will be graceful
     */
    public void unregisterProxyServer(HttpProxyServer proxyServer, boolean graceful) {
        synchronized (SERVER_REGISTRATION_LOCK) {
            boolean wasRegistered = registeredServers.remove(proxyServer);
            if (!wasRegistered) {
                log.warn("Attempted to unregister proxy server from ServerGroup that it was not registered with. Was the proxy unregistered twice?");
            }

            if (registeredServers.isEmpty()) {
                log.debug("Proxy server unregistered from ServerGroup. No proxy servers remain registered, so shutting down ServerGroup.");

                shutdown(graceful);
            } else {
                log.debug("Proxy server unregistered from ServerGroup. Not shutting down ServerGroup ({} proxy servers remain registered).", registeredServers.size());
            }
        }
    }

    /**
     * Shuts down all event loops owned by this server group.
     *
     * 关闭此服务器组拥有的所有事件循环
     *
     * @param graceful when true, event loops will "gracefully" terminate, waiting for submitted tasks to finish
     *  如果为true，事件循环将“优雅地”终止，等待提交的任务完成
     */
    private void shutdown(boolean graceful) {
        if (!stopped.compareAndSet(false, true)) {
            log.info("Shutdown requested, but ServerGroup is already stopped. Doing nothing.");

            return;
        }

        log.info("Shutting down server group event loops " + (graceful ? "(graceful)" : "(non-graceful)"));

        // loop through all event loops managed by this server group. this includes acceptor and worker event loops
        // for both TCP and UDP transport protocols.
        /*
         *遍历此服务器组管理的所有事件循环。这包括TCP和UDP传输协议的受主和工作程序事件循环。
         */
        List<EventLoopGroup> allEventLoopGroups = new ArrayList<EventLoopGroup>();

        for (ProxyThreadPools threadPools : protocolThreadPools.values()) {
            allEventLoopGroups.addAll(threadPools.getAllEventLoops());
        }

        for (EventLoopGroup group : allEventLoopGroups) {
            if (graceful) {
                group.shutdownGracefully();
            } else {
                group.shutdownGracefully(0, 0, TimeUnit.SECONDS);
            }
        }

        if (graceful) {
            for (EventLoopGroup group : allEventLoopGroups) {
                try {
                    group.awaitTermination(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();

                    log.warn("Interrupted while shutting down event loop");
                }
            }
        }

        log.debug("Done shutting down server group");
    }

    /**
     * Retrieves the client-to-proxy acceptor thread pool for the specified protocol. Initializes the pool if it has not
     * yet been initialized.
     * 检索指定协议的客户端到代理接受器线程池。如果尚未初始化池，请初始化。
     * <p>
     * This method is thread-safe; no external locking is necessary.
     * 该方法是线程安全的；无需外部锁定
     *
     * @param protocol transport protocol to retrieve the thread pool for
     * @return the client-to-proxy acceptor thread pool
     */
    public EventLoopGroup getClientToProxyAcceptorPoolForTransport(TransportProtocol protocol) {
        return getThreadPoolsForProtocol(protocol).getClientToProxyAcceptorPool();
    }

    /**
     * Retrieves the client-to-proxy acceptor worker pool for the specified protocol. Initializes the pool if it has not
     * yet been initialized.
     * 检索指定协议的客户端到代理接受者工作池。如果尚未初始化池，请初始化
     * <p>
     * This method is thread-safe; no external locking is necessary.
     * 该方法是线程安全的；无需外部锁定
     *
     * @param protocol transport protocol to retrieve the thread pool for
     * @return the client-to-proxy worker thread pool
     */
    public EventLoopGroup getClientToProxyWorkerPoolForTransport(TransportProtocol protocol) {
        return getThreadPoolsForProtocol(protocol).getClientToProxyWorkerPool();
    }

    /**
     * Retrieves the proxy-to-server worker thread pool for the specified protocol. Initializes the pool if it has not
     * yet been initialized.
     *
     * 检索指定协议的代理到服务器工作线程池。初始化池（如果尚未初始化）
     * <p>
     * This method is thread-safe; no external locking is necessary.
     * 该方法是线程安全的；无需外部锁定
     *
     * @param protocol transport protocol to retrieve the thread pool for
     * @return the proxy-to-server worker thread pool
     */
    public EventLoopGroup getProxyToServerWorkerPoolForTransport(TransportProtocol protocol) {
        return getThreadPoolsForProtocol(protocol).getProxyToServerWorkerPool();
    }

    /**
     * @return true if this ServerGroup has already been stopped
     * 如果此ServerGroup已经停止，则返回true
     */
    public boolean isStopped() {
        return stopped.get();
    }

}
