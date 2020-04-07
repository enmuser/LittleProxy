package org.littleshoot.proxy.impl;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;
import org.littleshoot.proxy.HttpFilters;

import javax.net.ssl.SSLEngine;

import static org.littleshoot.proxy.impl.ConnectionState.*;

/**
 * <p>
 * Base class for objects that represent a connection to/from our proxy.
 *
 * 代表与代理之间的连接的对象的基类
 * </p>
 * <p>
 * A ProxyConnection models a bidirectional message flow on top of a Netty
 *
 * ProxyConnection在Netty顶部对双向消息流进行建模
 * {@link Channel}.
 * </p>
 * <p>
 * The {@link #read(Object)} method is called whenever a new message arrives on
 * the underlying socket.
 *
 * 每当新消息到达底层套接字时，就会调用{@link #read（Object）}方法。
 * </p>
 * <p>
 * The {@link #write(Object)} method can be called by anyone wanting to write
 * data out of the connection.
 *
 * 任何想从连接中写入数据的人都可以调用{@link #write（Object）}方法。
 * </p>
 * <p>
 * ProxyConnection has a lifecycle and its current state within that lifecycle
 * is recorded as a {@link ConnectionState}. The allowed states and transitions
 * vary a little depending on the concrete implementation of ProxyConnection.
 * However, all ProxyConnections share the following lifecycle events:
 *
 * ProxyConnection具有生命周期，并且该生命周期内的当前状态被记录为{@link ConnectionState}。
 * 允许的状态和转换取决于ProxyConnection的具体实现而略有不同。
 * 但是，所有ProxyConnection共享以下生命周期事件
 * </p>
 * 
 * <ul>
 * <li>{@link #connected()} - Once the underlying channel is active, the
 * ProxyConnection is considered connected and moves into
 * {@link ConnectionState#AWAITING_INITIAL}. The Channel is recorded at this
 * time for later referencing.</li>
 *
 * {@link #connected（）}-基础通道处于活动状态后，ProxyConnection被视为已连接，
 * 并移至 {@link ConnectionState＃AWAITING_INITIAL}。
 * 此时会记录频道，以供以后参考
 *
 * <li>{@link #disconnected()} - When the underlying channel goes inactive, the
 * ProxyConnection moves into {@link ConnectionState#DISCONNECTED}</li>
 *
 * {@link #disconnected（）}-当基础频道变为非活动状态时，
 * ProxyConnection移入{@link ConnectionState＃DISCONNECTED}
 *
 * <li>{@link #becameWritable()} - When the underlying channel becomes
 * writeable, this callback is invoked.</li>
 *
 * {@link #becameWritable（）}-当基础通道变为可写时，将调用此回调
 * </ul>
 * 
 * <p>
 * By default, incoming data on the underlying channel is automatically read and
 * passed to the {@link #read(Object)} method. Reading can be stopped and
 * resumed using {@link #stopReading()} and {@link #resumeReading()}.
 *
 * 默认情况下，自动读取基础通道上的传入数据，并将传递给{@link #read（Object）}方法。
 * 可以使用{@link #stopReading（）}和{@link #resumeReading（）}停止阅读并继续
 * </p>
 * 
 * @param <I>
 *            the type of "initial" message. This will be either
 *            {@link HttpResponse} or {@link HttpRequest}.
 */
abstract class ProxyConnection<I extends HttpObject> extends
        SimpleChannelInboundHandler<Object> {
    protected final ProxyConnectionLogger LOG = new ProxyConnectionLogger(this);

    protected final DefaultHttpProxyServer proxyServer;
    protected final boolean runsAsSslClient;

    protected volatile ChannelHandlerContext ctx;
    protected volatile Channel channel;

    private volatile ConnectionState currentState;
    private volatile boolean tunneling = false;
    protected volatile long lastReadTime = 0;

    /**
     * If using encryption, this holds our {@link SSLEngine}.
     *
     * 如果使用加密，则保存我们的{@link SSLEngine}
     */
    protected volatile SSLEngine sslEngine;

    /**
     * Construct a new ProxyConnection.
     * 构造一个新的ProxyConnection
     * 
     * @param initialState
     *            the state in which this connection starts out
     *            连接开始的状态
     * @param proxyServer
     *            the {@link DefaultHttpProxyServer} in which we're running
     *            我们正在其中运行的{@link DefaultHttpProxyServer}
     *
     * @param runsAsSslClient
     *            determines whether this connection acts as an SSL client or
     *            server (determines who does the handshake)
     *            确定此连接是充当SSL客户端还是服务器（确定由谁进行握手）
     */

    protected ProxyConnection(ConnectionState initialState,
            DefaultHttpProxyServer proxyServer,
            boolean runsAsSslClient) {
        become(initialState);
        this.proxyServer = proxyServer;
        this.runsAsSslClient = runsAsSslClient;
    }

    /***************************************************************************
     * Reading
     **************************************************************************/

    /**
     * Read is invoked automatically by Netty as messages arrive on the socket.
     *
     * 当消息到达套接字时，Netty会自动调用读取
     * 
     * @param msg
     */
    protected void read(Object msg) {
        LOG.debug("Reading: {}", msg);

        lastReadTime = System.currentTimeMillis();

        if (tunneling) {
            // In tunneling mode, this connection is simply shoveling bytes
            // 在隧道模式下，此连接只是铲除字节
            readRaw((ByteBuf) msg);
        } else {
            // If not tunneling, then we are always dealing with HttpObjects.
            // 如果不进行隧道传输，那么我们将始终处理HttpObjects
            readHTTP((HttpObject) msg);
        }
    }

    /**
     * Handles reading {@link HttpObject}s.
     * 处理读取{@link HttpObject}的过程
     * 
     * @param httpObject
     */
    @SuppressWarnings("unchecked")
    private void readHTTP(HttpObject httpObject) {
        ConnectionState nextState = getCurrentState();
        switch (getCurrentState()) {
        case AWAITING_INITIAL:
            if (httpObject instanceof HttpMessage) {
                nextState = readHTTPInitial((I) httpObject);
            } else {
                // Similar to the AWAITING_PROXY_AUTHENTICATION case below, we may enter an AWAITING_INITIAL
                // state if the proxy responded to an earlier request with a 502 or 504 response, or a short-circuit
                // response from a filter. The client may have sent some chunked HttpContent associated with the request
                // after the short-circuit response was sent. We can safely drop them.
                /*
                  与下面的AWAITING_PROXY_AUTHENTICATION情况类似，如果代理使用502或504响应或来自过滤器的短路响应来响应较早的请求，
                  则我们可能会进入AWAITING_INITIAL状态。 发送短路响应后，客户端可能已发送了与请求相关联的某些HttpContent块。
                  我们可以放心地放下它们。
                 */
                // 删除消息，因为HTTP对象不是HttpMessage。 HTTP对象可能是短路响应中的孤立内容
                LOG.debug("Dropping message because HTTP object was not an HttpMessage. HTTP object may be orphaned content from a short-circuited response. Message: {}", httpObject);
            }
            break;
        case AWAITING_CHUNK:
            HttpContent chunk = (HttpContent) httpObject;
            readHTTPChunk(chunk);
            nextState = ProxyUtils.isLastChunk(chunk) ? AWAITING_INITIAL
                    : AWAITING_CHUNK;
            break;
        case AWAITING_PROXY_AUTHENTICATION:
            if (httpObject instanceof HttpRequest) {
                // Once we get an HttpRequest, try to process it as usual
                // 一旦获得HttpRequest，请尝试照常处理它
                nextState = readHTTPInitial((I) httpObject);
            } else {
                // Anything that's not an HttpRequest that came in while
                // we're pending authentication gets dropped on the floor. This
                // can happen if the connected host already sent us some chunks
                // (e.g. from a POST) after an initial request that turned out
                // to require authentication.
                /*
                 * 在我们等待身份验证时出现的不是HttpRequest的所有内容都会被丢弃。
                 * 如果连接的主机在最初要求进行身份验证的初始请求之后已经向我们发送了一些数据块（例如从POST发送），
                 * 则会发生这种情况。
                 */
            }
            break;
        case CONNECTING:
            // 尝试从正在连接的连接中读取数据。这不应该发生
            LOG.warn("Attempted to read from connection that's in the process of connecting.  This shouldn't happen.");
            break;
        case NEGOTIATING_CONNECT:
            // 尝试从正在协商HTTP CONNECT的连接中读取。这可能是分块CONNECT的LastHttpContent
            LOG.debug("Attempted to read from connection that's in the process of negotiating an HTTP CONNECT.  This is probably the LastHttpContent of a chunked CONNECT.");
            break;
        case AWAITING_CONNECT_OK:
            // AWAITING_CONNECT_OK应该已经由ProxyToServerConnection.read（）处理。
            LOG.warn("AWAITING_CONNECT_OK should have been handled by ProxyToServerConnection.read()");
            break;
        case HANDSHAKING:
            // 尝试从握手过程中的连接中读取信息。这不应该发生。
            LOG.warn(
                    "Attempted to read from connection that's in the process of handshaking.  This shouldn't happen.",
                    channel);
            break;
        case DISCONNECT_REQUESTED:
        case DISCONNECTED:
            // 由于连接已关闭或即将关闭，因此忽略消息
            LOG.info("Ignoring message since the connection is closed or about to close");
            break;
        }
        become(nextState);
    }

    /**
     * Implement this to handle reading the initial object (e.g.
     * {@link HttpRequest} or {@link HttpResponse}).
     * 实施此操作以处理读取初始对象
     * （例如{@link HttpRequest}或{@link HttpResponse}）。
     * 
     * @param httpObject
     * @return
     */
    protected abstract ConnectionState readHTTPInitial(I httpObject);

    /**
     * Implement this to handle reading a chunk in a chunked transfer.
     *
     * 实现此功能以处理读取分块传输中的块
     * 
     * @param chunk
     */
    protected abstract void readHTTPChunk(HttpContent chunk);

    /**
     * Implement this to handle reading a raw buffer as they are used in HTTP
     * tunneling.
     *
     * 实现此功能以处理读取HTTP隧道中使用的原始缓冲区的过程
     * 
     * @param buf
     */
    protected abstract void readRaw(ByteBuf buf);

    /***************************************************************************
     * Writing
     **************************************************************************/

    /**
     * This method is called by users of the ProxyConnection to send stuff out
     * over the socket.
     * ProxyConnection的用户调用此方法以通过套接字将内容发送出去
     * 
     * @param msg
     */
    void write(Object msg) {
        if (msg instanceof ReferenceCounted) {
            LOG.debug("Retaining reference counted message");
            ((ReferenceCounted) msg).retain();
        }

        doWrite(msg);
    }

    void doWrite(Object msg) {
        LOG.debug("Writing: {}", msg);

        try {
            if (msg instanceof HttpObject) {
                writeHttp((HttpObject) msg);
            } else {
                writeRaw((ByteBuf) msg);
            }
        } finally {
            LOG.debug("Wrote: {}", msg);
        }
    }

    /**
     * Writes HttpObjects to the connection asynchronously.
     * 将HttpObjects异步写入连接
     * 
     * @param httpObject
     */
    protected void writeHttp(HttpObject httpObject) {
        if (ProxyUtils.isLastChunk(httpObject)) {
            channel.write(httpObject);
            // 编写一个空缓冲区来表示分块传输的结束
            LOG.debug("Writing an empty buffer to signal the end of our chunked transfer");
            writeToChannel(Unpooled.EMPTY_BUFFER);
        } else {
            writeToChannel(httpObject);
        }
    }

    /**
     * Writes raw buffers to the connection.
     * 将原始缓冲区写入连接
     * 
     * @param buf
     */
    protected void writeRaw(ByteBuf buf) {
        writeToChannel(buf);
    }

    protected ChannelFuture writeToChannel(final Object msg) {
        return channel.writeAndFlush(msg);
    }

    /***************************************************************************
     * Lifecycle
     **************************************************************************/

    /**
     * This method is called as soon as the underlying {@link Channel} is
     * connected. Note that for proxies with complex {@link ConnectionFlow}s
     * that include SSL handshaking and other such things, just because the
     * {@link Channel} is connected doesn't mean that our connection is fully
     * established.
     *
     * 一旦连接了基础{@link Channel}，就会立即调用此方法。
     * 请注意，对于具有复杂的{@link ConnectionFlow} s的代理，
     * 其中包括SSL握手和其他类似操作，仅由于{@link Channel}已连接并不意味着我们的连接已完全建立
     */
    protected void connected() {
        LOG.debug("Connected");
    }

    /**
     * This method is called as soon as the underlying {@link Channel} becomes
     * disconnected.
     *
     * 基础{@link Channel}一旦断开连接，就会立即调用此方法
     */
    protected void disconnected() {
        become(DISCONNECTED);
        LOG.debug("Disconnected");
    }

    /**
     * This method is called when the underlying {@link Channel} times out due
     * to an idle timeout.
     *
     * 当底层的{@link Channel}由于空闲超时而超时时，将调用此方法
     */
    protected void timedOut() {
        disconnect();
    }

    /**
     * <p>
     * Enables tunneling on this connection by dropping the HTTP related
     * encoders and decoders, as well as idle timers.
     * 通过删除与HTTP相关的编码器和解码器以及空闲计时器，在此连接上启用隧道。
     * </p>
     * 
     * <p>
     * Note - the work is done on the {@link ChannelHandlerContext}'s executor
     * because {@link ChannelPipeline#remove(String)} can deadlock if called
     * directly.
     *
     * 注意-该工作是在{@link ChannelHandlerContext}的执行程序*上完成的，因为如果直接调用，
     * {@ link ChannelPipeline ＃remove（String）} 可能会死锁
     *
     * </p>
     */
    protected ConnectionFlowStep StartTunneling = new ConnectionFlowStep(
            this, NEGOTIATING_CONNECT) {
        @Override
        boolean shouldSuppressInitialRequest() {
            return true;
        }

        protected Future execute() {
            try {
                ChannelPipeline pipeline = ctx.pipeline();
                if (pipeline.get("encoder") != null) {
                    pipeline.remove("encoder");
                }
                if (pipeline.get("responseWrittenMonitor") != null) {
                    pipeline.remove("responseWrittenMonitor");
                }
                if (pipeline.get("decoder") != null) {
                    pipeline.remove("decoder");
                }
                if (pipeline.get("requestReadMonitor") != null) {
                    pipeline.remove("requestReadMonitor");
                }
                tunneling = true;
                return channel.newSucceededFuture();
            } catch (Throwable t) {
                return channel.newFailedFuture(t);
            }
        }
    };

    /**
     * Encrypts traffic on this connection with SSL/TLS.
     * 使用SSL / TLS加密此连接上的流量。
     * 
     * @param sslEngine
     *            the {@link SSLEngine} for doing the encryption
     *            {@link SSLEngine}进行加密
     * @param authenticateClients
     *            determines whether to authenticate clients or not
     *            确定是否对客户端进行身份验证
     * @return a Future for when the SSL handshake has completed
     * SSL握手完成的未来
     */
    protected Future<Channel> encrypt(SSLEngine sslEngine,
            boolean authenticateClients) {
        return encrypt(ctx.pipeline(), sslEngine, authenticateClients);
    }

    /**
     * Encrypts traffic on this connection with SSL/TLS.
     * 使用SSL / TLS加密此连接上的流量
     * 
     * @param pipeline
     *            the ChannelPipeline on which to enable encryption
     * @param sslEngine
     *            the {@link SSLEngine} for doing the encryption
     * @param authenticateClients
     *            determines whether to authenticate clients or not
     * @return a Future for when the SSL handshake has completed
     */
    protected Future<Channel> encrypt(ChannelPipeline pipeline,
            SSLEngine sslEngine,
            boolean authenticateClients) {
        LOG.debug("Enabling encryption with SSLEngine: {}",
                sslEngine);
        this.sslEngine = sslEngine;
        sslEngine.setUseClientMode(runsAsSslClient);
        sslEngine.setNeedClientAuth(authenticateClients);
        if (null != channel) {
            channel.config().setAutoRead(true);
        }
        SslHandler handler = new SslHandler(sslEngine);
        if(pipeline.get("ssl") == null) {
            pipeline.addFirst("ssl", handler);
        } else {
            // The second SSL handler is added to handle the case
            // where the proxy (running as MITM) has to chain with
            // another SSL enabled proxy. The second SSL handler
            // is to perform SSL with the server.
            /*
              添加了第二个SSL处理程序，以处理代理（以MITM运行）必须与另一个启用SSL的代理链接的情况。
              第二个SSL处理程序是对服务器执行SSL。
             */
            pipeline.addAfter("ssl", "sslWithServer", handler);
        }
        return handler.handshakeFuture();
    }

    /**
     * Encrypts the channel using the provided {@link SSLEngine}.
     *
     * 使用提供的{@link SSLEngine}加密频道
     * 
     * @param sslEngine
     *            the {@link SSLEngine} for doing the encryption
     */
    protected ConnectionFlowStep EncryptChannel(
            final SSLEngine sslEngine) {

        return new ConnectionFlowStep(this, HANDSHAKING) {
            @Override
            boolean shouldExecuteOnEventLoop() {
                return false;
            }

            @Override
            protected Future<?> execute() {
                return encrypt(sslEngine, !runsAsSslClient);
            }
        };
    };

    /**
     * Enables decompression and aggregation of content, which is useful for
     * certain types of filtering activity.
     * 启用内容的解压缩和聚合，这对于某些类型的过滤活动非常有用
     * 
     * @param pipeline
     * @param numberOfBytesToBuffer
     */
    protected void aggregateContentForFiltering(ChannelPipeline pipeline,
            int numberOfBytesToBuffer) {
        pipeline.addLast("inflater", new HttpContentDecompressor());
        pipeline.addLast("aggregator", new HttpObjectAggregator(
                numberOfBytesToBuffer));
    }

    /**
     * Callback that's invoked if this connection becomes saturated.
     * 如果此连接饱和，则调用此回调。
     */
    protected void becameSaturated() {
        LOG.debug("Became saturated");
    }

    /**
     * Callback that's invoked when this connection becomes writeable again.
     * 当此连接再次变为可写状态时调用的回调。
     */
    protected void becameWritable() {
        LOG.debug("Became writeable");
    }

    /**
     * Override this to handle exceptions that occurred during asynchronous
     * processing on the {@link Channel}.
     * 重写此方法以处理在{@link Channel}上进行异步处理期间发生的异常
     * 
     * @param cause
     */
    protected void exceptionCaught(Throwable cause) {
    }

    /***************************************************************************
     * State/Management
     **************************************************************************/
    /**
     * Disconnects. This will wait for pending writes to be flushed before
     * disconnecting.
     *
     * 断开连接。 这将等待刷新挂起的写入，然后再断开连接。
     * 
     * @return Future<Void> for when we're done disconnecting. If we weren't
     *         connected, this returns null.
     *         当我们断开连接时的Future <Void>。如果没有连接，则返回null
     */
    Future<Void> disconnect() {
        if (channel == null) {
            return null;
        } else {
            final Promise<Void> promise = channel.newPromise();
            writeToChannel(Unpooled.EMPTY_BUFFER).addListener(
                    new GenericFutureListener<Future<? super Void>>() {
                        @Override
                        public void operationComplete(
                                Future<? super Void> future)
                                throws Exception {
                            closeChannel(promise);
                        }
                    });
            return promise;
        }
    }

    private void closeChannel(final Promise<Void> promise) {
        channel.close().addListener(
                new GenericFutureListener<Future<? super Void>>() {
                    public void operationComplete(
                            Future<? super Void> future)
                            throws Exception {
                        if (future
                                .isSuccess()) {
                            promise.setSuccess(null);
                        } else {
                            promise.setFailure(future
                                    .cause());
                        }
                    };
                });
    }

    /**
     * Indicates whether or not this connection is saturated (i.e. not
     * writeable).
     * 指示此连接是否饱和（即不可写）
     * 
     * @return
     */
    protected boolean isSaturated() {
        return !this.channel.isWritable();
    }

    /**
     * Utility for checking current state.
     * 用于检查当前状态的实用程序
     * 
     * @param state
     * @return
     */
    protected boolean is(ConnectionState state) {
        return currentState == state;
    }

    /**
     * If this connection is currently in the process of going through a
     * {@link ConnectionFlow}, this will return true.
     *
     * 如果此连接当前正在通过{@link ConnectionFlow}处理，则将返回true
     * 
     * @return
     */
    protected boolean isConnecting() {
        return currentState.isPartOfConnectionFlow();
    }

    /**
     * Udpates the current state to the given value.
     * 将当前状态更新为给定值
     * 
     * @param state
     */
    protected void become(ConnectionState state) {
        this.currentState = state;
    }

    protected ConnectionState getCurrentState() {
        return currentState;
    }

    public boolean isTunneling() {
        return tunneling;
    }

    public SSLEngine getSslEngine() {
        return sslEngine;
    }

    /**
     * Call this to stop reading.
     * 叫这个停止阅读
     */
    protected void stopReading() {
        LOG.debug("Stopped reading");
        this.channel.config().setAutoRead(false);
    }

    /**
     * Call this to resume reading.
     * 打电话给它继续阅读
     */
    protected void resumeReading() {
        LOG.debug("Resumed reading");
        this.channel.config().setAutoRead(true);
    }

    /**
     * Request the ProxyServer for Filters.
     *
     * 要求ProxyServer进行过滤
     * 
     * By default, no-op filters are returned by DefaultHttpProxyServer.
     * Subclasses of ProxyConnection can change this behaviour.
     *
     * 默认情况下，DefaultHttpProxyServer返回无操作筛选器。
     * ProxyConnection的子类可以更改此行为
     * 
     * @param httpRequest
     *            Filter attached to the give HttpRequest (if any)
     * @return
     */
    protected HttpFilters getHttpFiltersFromProxyServer(HttpRequest httpRequest) {
        return proxyServer.getFiltersSource().filterRequest(httpRequest, ctx);
    }

    ProxyConnectionLogger getLOG() {
        return LOG;
    }

    /***************************************************************************
     * Adapting the Netty API
     **************************************************************************/
    @Override
    protected final void channelRead0(ChannelHandlerContext ctx, Object msg)
            throws Exception {
        read(msg);
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        try {
            this.ctx = ctx;
            this.channel = ctx.channel();
            this.proxyServer.registerChannel(ctx.channel());
        } finally {
            super.channelRegistered(ctx);
        }
    }

    /**
     * Only once the Netty Channel is active to we recognize the ProxyConnection
     * as connected.
     * 仅当Netty Channel处于活动状态时，我们才将ProxyConnection 识别为已连接
     */
    @Override
    public final void channelActive(ChannelHandlerContext ctx) throws Exception {
        try {
            connected();
        } finally {
            super.channelActive(ctx);
        }
    }

    /**
     * As soon as the Netty Channel is inactive, we recognize the
     * ProxyConnection as disconnected.
     * 一旦Netty Channel处于非活动状态，我们就会将ProxyConnection识别为已断开连接
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        try {
            disconnected();
        } finally {
            super.channelInactive(ctx);
        }
    }

    @Override
    public final void channelWritabilityChanged(ChannelHandlerContext ctx)
            throws Exception {
        LOG.debug("Writability changed. Is writable: {}", channel.isWritable());
        try {
            if (this.channel.isWritable()) {
                becameWritable();
            } else {
                becameSaturated();
            }
        } finally {
            super.channelWritabilityChanged(ctx);
        }
    }

    @Override
    public final void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
            throws Exception {
        exceptionCaught(cause);
    }

    /**
     * <p>
     * We're looking for {@link IdleStateEvent}s to see if we need to
     * disconnect.
     * 我们正在寻找{@link IdleStateEvent}来查看是否需要断开连接
     * </p>
     * 
     * <p>
     * Note - we don't care what kind of IdleState we got. Thanks to <a
     * href="https://github.com/qbast">qbast</a> for pointing this out.
     * </p>
     */
    @Override
    public final void userEventTriggered(ChannelHandlerContext ctx, Object evt)
            throws Exception {
        try {
            if (evt instanceof IdleStateEvent) {
                LOG.debug("Got idle");
                timedOut();
            }
        } finally {
            super.userEventTriggered(ctx, evt);
        }
    }

    /***************************************************************************
     * Activity Tracking/Statistics
     **************************************************************************/

    /**
     * Utility handler for monitoring bytes read on this connection.
     * 实用程序处理程序，用于监视在此连接上读取的字节
     */
    @Sharable
    protected abstract class BytesReadMonitor extends
            ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg)
                throws Exception {
            try {
                if (msg instanceof ByteBuf) {
                    bytesRead(((ByteBuf) msg).readableBytes());
                }
            } catch (Throwable t) {
                LOG.warn("Unable to record bytesRead", t);
            } finally {
                super.channelRead(ctx, msg);
            }
        }

        protected abstract void bytesRead(int numberOfBytes);
    }

    /**
     * Utility handler for monitoring requests read on this connection.
     * 用于监视在此连接上读取的请求的实用程序处理程序
     */
    @Sharable
    protected abstract class RequestReadMonitor extends
            ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg)
                throws Exception {
            try {
                if (msg instanceof HttpRequest) {
                    requestRead((HttpRequest) msg);
                }
            } catch (Throwable t) {
                LOG.warn("Unable to record bytesRead", t);
            } finally {
                super.channelRead(ctx, msg);
            }
        }

        protected abstract void requestRead(HttpRequest httpRequest);
    }

    /**
     * Utility handler for monitoring responses read on this connection.
     * 实用程序处理程序，用于监视在此连接上读取的响应
     */
    @Sharable
    protected abstract class ResponseReadMonitor extends
            ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg)
                throws Exception {
            try {
                if (msg instanceof HttpResponse) {
                    responseRead((HttpResponse) msg);
                }
            } catch (Throwable t) {
                LOG.warn("Unable to record bytesRead", t);
            } finally {
                super.channelRead(ctx, msg);
            }
        }

        protected abstract void responseRead(HttpResponse httpResponse);
    }

    /**
     * Utility handler for monitoring bytes written on this connection.
     * 实用程序处理程序，用于监视在此连接上写入的字节
     */
    @Sharable
    protected abstract class BytesWrittenMonitor extends
            ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx,
                Object msg, ChannelPromise promise)
                throws Exception {
            try {
                if (msg instanceof ByteBuf) {
                    bytesWritten(((ByteBuf) msg).readableBytes());
                }
            } catch (Throwable t) {
                LOG.warn("Unable to record bytesRead", t);
            } finally {
                super.write(ctx, msg, promise);
            }
        }

        protected abstract void bytesWritten(int numberOfBytes);
    }

    /**
     * Utility handler for monitoring requests written on this connection.
     * 实用程序处理程序，用于监视在此连接上编写的请求。
     */
    @Sharable
    protected abstract class RequestWrittenMonitor extends
            ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx,
                Object msg, ChannelPromise promise)
                throws Exception {
            HttpRequest originalRequest = null;
            if (msg instanceof HttpRequest) {
                originalRequest = (HttpRequest) msg;
            }

            if (null != originalRequest) {
                requestWriting(originalRequest);
            }

            super.write(ctx, msg, promise);

            if (null != originalRequest) {
                requestWritten(originalRequest);
            }

            if (msg instanceof HttpContent) {
                contentWritten((HttpContent) msg);
            }
        }

        /**
         * Invoked immediately before an HttpRequest is written.
         * 在写入HttpRequest之前立即调用
         */
        protected abstract void requestWriting(HttpRequest httpRequest);

        /**
         * Invoked immediately after an HttpRequest has been sent.
         * 发送HttpRequest后立即调用
         */
        protected abstract void requestWritten(HttpRequest httpRequest);

        /**
         * Invoked immediately after an HttpContent has been sent.
         * 发送HttpContent后立即调用
         */
        protected abstract void contentWritten(HttpContent httpContent);
    }

    /**
     * Utility handler for monitoring responses written on this connection.
     * 实用程序处理程序，用于监视在此连接上编写的响应
     */
    @Sharable
    protected abstract class ResponseWrittenMonitor extends
            ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx,
                Object msg, ChannelPromise promise)
                throws Exception {
            try {
                if (msg instanceof HttpResponse) {
                    responseWritten(((HttpResponse) msg));
                }
            } catch (Throwable t) {
                LOG.warn("Error while invoking responseWritten callback", t);
            } finally {
                super.write(ctx, msg, promise);
            }
        }

        protected abstract void responseWritten(HttpResponse httpResponse);
    }

}
