package org.littleshoot.proxy.impl;

import io.netty.util.concurrent.Future;

/**
 * Represents a phase in a {@link ConnectionFlow}.
 * 代表{@link ConnectionFlow}中的一个阶段
 */
abstract class ConnectionFlowStep {
    private final ProxyConnectionLogger LOG;
    private final ProxyConnection connection;
    private final ConnectionState state;

    /**
     * Construct a new step in a connection flow.
     * 在连接流程中构建新的步骤。
     * 
     * @param connection
     *            the connection that we're working on
     *            我们正在努力的联系
     * @param state
     *            the state that the connection will show while we're processing
     *            this step
     *            在我们正在处理此步骤时连接将显示的状态
     */
    ConnectionFlowStep(ProxyConnection connection,
            ConnectionState state) {
        super();
        this.connection = connection;
        this.state = state;
        this.LOG = connection.getLOG();
    }

    ProxyConnection getConnection() {
        return connection;
    }

    ConnectionState getState() {
        return state;
    }

    /**
     * Indicates whether or not to suppress the initial request. Defaults to
     * false, can be overridden.
     * 指示是否禁止初始请求。默认为false，可以覆盖
     * 
     * @return
     */
    boolean shouldSuppressInitialRequest() {
        return false;
    }

    /**
     * <p>
     * Indicates whether or not this step should be executed on the channel's
     * event loop. Defaults to true, can be overridden.
     * 指示是否应在通道的事件循环上执行此步骤。默认为true，可以覆盖
     * </p>
     * 
     * <p>
     * If this step modifies the pipeline, for example by adding/removing
     * handlers, it's best to make it execute on the event loop.
     * 如果此步骤（例如通过添加/删除处理程序）修改了管道，则最好使其在事件循环上执行
     * </p>
     * 
     * 
     * @return
     */
    boolean shouldExecuteOnEventLoop() {
        return true;
    }

    /**
     * Implement this method to actually do the work involved in this step of
     * the flow.
     * 实现此方法以实际完成流程的此步骤中涉及的工作
     * 
     * @return
     */
    protected abstract Future execute();

    /**
     * When the flow determines that this step was successful, it calls into
     * this method. The default implementation simply continues with the flow.
     * Other implementations may choose to not continue and instead wait for a
     * message or something like that.
     *
     * 当流程确定此步骤成功时，它将调用此方法。默认实现只是继续执行流程。
     * 其他实现可能选择不继续，而是等待*消息或类似的消息
     * 
     * @param flow
     */
    void onSuccess(ConnectionFlow flow) {
        flow.advance();
    }

    /**
     * <p>
     * Any messages that are read from the underlying connection while we're at
     * this step of the connection flow are passed to this method.
     *
     * 在连接流程的此步骤中，从基础连接读取的所有消息都将传递给此方法。
     * </p>
     * 
     * <p>
     * The default implementation ignores the message and logs this, since we
     * weren't really expecting a message here.
     *
     * 默认实现会忽略该消息并将其记录下来，因为我们并不是真正希望在此处收到消息。
     * </p>
     * 
     * <p>
     * Some {@link ConnectionFlowStep}s do need to read the messages, so they
     * override this method as appropriate.
     *
     * 某些{@link ConnectionFlowStep}确实需要阅读消息，因此它们会适当覆盖此方法。
     * </p>
     * 
     * @param flow
     *            our {@link ConnectionFlow}
     * @param msg
     *            the message read from the underlying connection
     */
    void read(ConnectionFlow flow, Object msg) {
        //在连接过程中收到消息
        LOG.debug("Received message while in the middle of connecting: {}", msg);
    }

    @Override
    public String toString() {
        return state.toString();
    }

}
