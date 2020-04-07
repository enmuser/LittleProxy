package org.littleshoot.proxy.impl;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Coordinates the various steps involved in establishing a connection, such as
 * establishing a socket connection, SSL handshaking, HTTP CONNECT request
 * processing, and so on.
 *
 * 协调建立连接所涉及的各个步骤，
 * 例如建立套接字连接，SSL握手，HTTP CONNECT请求处理等
 */
class ConnectionFlow {
    private Queue<ConnectionFlowStep> steps = new ConcurrentLinkedQueue<ConnectionFlowStep>();

    private final ClientToProxyConnection clientConnection;
    private final ProxyToServerConnection serverConnection;
    private volatile ConnectionFlowStep currentStep;
    private volatile boolean suppressInitialRequest = false;
    private final Object connectLock;
    
    /**
     * Construct a new {@link ConnectionFlow} for the given client and server
     * connections.
     * 为给定的客户端和服务器连接构建一个新的{@link ConnectionFlow}
     * 
     * @param clientConnection
     * @param serverConnection
     * @param connectLock
     *            an object that's shared by {@link ConnectionFlow} and
     *            {@link ProxyToServerConnection} and that is used for
     *            synchronizing the reader and writer threads that are both
     *            involved during the establishing of a connection.
     *            由{@link ConnectionFlow}和@link ProxyToServerConnection}共享的对象，
     *            用于同步在建立连接过程中涉及的读取器和写入器线程
     */
    ConnectionFlow(
            ClientToProxyConnection clientConnection,
            ProxyToServerConnection serverConnection,
            Object connectLock) {
        super();
        this.clientConnection = clientConnection;
        this.serverConnection = serverConnection;
        this.connectLock = connectLock;
    }

    /**
     * Add a {@link ConnectionFlowStep} to this flow.
     * 在此流程中添加一个{@link ConnectionFlowStep}
     * 
     * @param step
     * @return
     */
    ConnectionFlow then(ConnectionFlowStep step) {
        steps.add(step);
        return this;
    }

    /**
     * While we're in the process of connecting, any messages read by the
     * {@link ProxyToServerConnection} are passed to this method, which passes
     * it on to {@link ConnectionFlowStep#read(ConnectionFlow, Object)} for the
     * current {@link ConnectionFlowStep}.
     * 在我们进行连接的过程中，{@link ProxyToServerConnection}读取的所有消息都将传递给此方法，
     * 该方法会将传递给{@link ConnectionFlowStep＃read（readflow，Object）} @link ConnectionFlowStep}。
     * 
     * @param msg
     */
    void read(Object msg) {
        if (this.currentStep != null) {
            this.currentStep.read(this, msg);
        }
    }

    /**
     * Starts the connection flow, notifying the {@link ClientToProxyConnection}
     * that we've started.
     *
     * 开始连接流程，通知{@link ClientToProxyConnection} 我们已经开始
     */
    void start() {
        clientConnection.serverConnectionFlowStarted(serverConnection);
        advance();
    }

    /**
     * <p>
     * Advances the flow. {@link #advance()} will be called until we're either
     * out of steps, or a step has failed.
     * 推进流程。 {@link #advance（）}将被调用，直到我们步数不足或步骤失败。
     * </p>
     */
    void advance() {
        currentStep = steps.poll();
        if (currentStep == null) {
            succeed();
        } else {
            processCurrentStep();
        }
    }

    /**
     * <p>
     * Process the current {@link ConnectionFlowStep}. With each step, we:
     * 处理当前的{@link ConnectionFlowStep}。每一步，我们
     * </p>
     * 
     * <ol>
     * <li>Change the state of the associated {@link ProxyConnection} to the
     * value of {@link ConnectionFlowStep#getState()}</li>
     * 将关联的{@link ProxyConnection}的状态更改为{@link ConnectionFlowStep＃getState（）}的值
     *
     * <li>Call {@link ConnectionFlowStep#execute()}</li>
     * 调用 {@link ConnectionFlowStep＃execute（）}
     *
     * <li>On completion of the {@link Future} returned by
     * {@link ConnectionFlowStep#execute()}, check the success.</li>
     * 由{@link ConnectionFlowStep＃execute（）}返回的{@link Future}完成后，检查是否成功。
     *
     * <li>If successful, we call back into
     * {@link ConnectionFlowStep#onSuccess(ConnectionFlow)}.</li>
     * 如果成功，我们将回拨至{@link ConnectionFlowStep＃onSuccess（ConnectionFlow）}。
     *
     * <li>If unsuccessful, we call {@link #fail()}, stopping the connection
     * flow</li>
     * 如果不成功，我们调用{@link #fail（）}，停止连接流
     * </ol>
     */
    private void processCurrentStep() {
        final ProxyConnection connection = currentStep.getConnection();
        final ProxyConnectionLogger LOG = connection.getLOG();

        LOG.debug("Processing connection flow step: {}", currentStep);
        connection.become(currentStep.getState());
        suppressInitialRequest = suppressInitialRequest
                || currentStep.shouldSuppressInitialRequest();

        if (currentStep.shouldExecuteOnEventLoop()) {
            connection.ctx.executor().submit(new Runnable() {
                @Override
                public void run() {
                    doProcessCurrentStep(LOG);
                }
            });
        } else {
            doProcessCurrentStep(LOG);
        }
    }

    /**
     * Does the work of processing the current step, checking the result and
     * handling success/failure.
     *
     * 完成当前步骤的处理，检查结果并处理成功/失败
     * 
     * @param LOG
     */
    @SuppressWarnings("unchecked")
    private void doProcessCurrentStep(final ProxyConnectionLogger LOG) {
        currentStep.execute().addListener(
                new GenericFutureListener<Future<?>>() {
                    public void operationComplete(
                            io.netty.util.concurrent.Future<?> future)
                            throws Exception {
                        synchronized (connectLock) {
                            if (future.isSuccess()) {
                                LOG.debug("ConnectionFlowStep succeeded");
                                currentStep
                                        .onSuccess(ConnectionFlow.this);
                            } else {
                                LOG.debug("ConnectionFlowStep failed",
                                        future.cause());
                                fail(future.cause());
                            }
                        }
                    };
                });
    }

    /**
     * Called when the flow is complete and successful. Notifies the
     * {@link ProxyToServerConnection} that we succeeded.
     * 在流程完成且成功时调用。通知{@link ProxyToServerConnection}成功。
     */
    void succeed() {
        synchronized (connectLock) {
            serverConnection.getLOG().debug(
                    "Connection flow completed successfully: {}", currentStep);
            serverConnection.connectionSucceeded(!suppressInitialRequest);
            notifyThreadsWaitingForConnection();
        }
    }

    /**
     * Called when the flow fails at some {@link ConnectionFlowStep}.
     * Disconnects the {@link ProxyToServerConnection} and informs the
     * {@link ClientToProxyConnection} that our connection failed.
     *
     * 当流程在某个{@link ConnectionFlowStep}失败时调用。
     * 断开{@link ProxyToServerConnection}的连接，并通知 {@link ClientToProxyConnection}我们的连接失败。
     */
    @SuppressWarnings("unchecked")
    void fail(final Throwable cause) {
        final ConnectionState lastStateBeforeFailure = serverConnection
                .getCurrentState();
        serverConnection.disconnect().addListener(
                new GenericFutureListener() {
                    @Override
                    public void operationComplete(Future future)
                            throws Exception {
                        synchronized (connectLock) {
                            if (!clientConnection.serverConnectionFailed(
                                    serverConnection,
                                    lastStateBeforeFailure,
                                    cause)) {
                                // the connection to the server failed and we are not retrying, so transition to the
                                // DISCONNECTED state
                                //与服务器的连接失败，并且我们不重试，因此过渡到DISCONNECTED状态
                                serverConnection.become(ConnectionState.DISCONNECTED);

                                // We are not retrying our connection, let anyone waiting for a connection know that we're done
                                // 我们不会重试连接，请等待连接的任何人都知道我们已完成
                                notifyThreadsWaitingForConnection();
                            }
                        }
                    }
                });
    }

    /**
     * Like {@link #fail(Throwable)} but with no cause.
     * 类似于{@link #fail（Throwable）}，但没有原因
     */
    void fail() {
        fail(null);
    }

    /**
     * Once we've finished recording our connection and written our initial
     * request, we can notify anyone who is waiting on the connection that it's
     * okay to proceed.
     * 一旦我们完成了对连接的记录并编写了初始请求，我们就可以通知正在等待连接的任何人都可以继续进行
     */
    private void notifyThreadsWaitingForConnection() {
        connectLock.notifyAll();
    }

}
