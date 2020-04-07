package org.littleshoot.proxy.impl;

import com.google.common.io.BaseEncoding;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.apache.commons.lang3.StringUtils;
import org.littleshoot.proxy.ActivityTracker;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.FullFlowContext;
import org.littleshoot.proxy.HttpFilters;
import org.littleshoot.proxy.HttpFiltersAdapter;
import org.littleshoot.proxy.ProxyAuthenticator;
import org.littleshoot.proxy.SslEngineSource;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.littleshoot.proxy.impl.ConnectionState.AWAITING_CHUNK;
import static org.littleshoot.proxy.impl.ConnectionState.AWAITING_INITIAL;
import static org.littleshoot.proxy.impl.ConnectionState.AWAITING_PROXY_AUTHENTICATION;
import static org.littleshoot.proxy.impl.ConnectionState.DISCONNECT_REQUESTED;
import static org.littleshoot.proxy.impl.ConnectionState.NEGOTIATING_CONNECT;

/**
 * <p>
 * Represents a connection from a client to our proxy. Each
 * ClientToProxyConnection can have multiple {@link ProxyToServerConnection}s,
 * at most one per outbound host:port.
 * 表示从客户端到我们的代理的连接。 每个ClientToProxyConnection可以具有多个{@link ProxyToServerConnection}，每个出站主机：端口最多一个。
 * </p>
 * 
 * <p>
 * Once a ProxyToServerConnection has been created for a given server, it is
 * continually reused. The ProxyToServerConnection goes through its own
 * lifecycle of connects and disconnects, with different underlying
 * {@link Channel}s, but only a single ProxyToServerConnection object is used
 * per server. The one exception to this is CONNECT tunneling - if a connection
 * has been used for CONNECT tunneling, that connection will never be reused.
 *
 * 一旦为给定服务器创建了ProxyToServerConnection，它将被连续重用。
 * ProxyToServerConnection经历其自身的连接和断开连接的生命周期，具有不同的基础{@link Channel}，
 * 但每个服务器仅使用单个ProxyToServerConnection对象。
 * 一个例外是CONNECT隧道-如果连接已用于CONNECT隧道，则该连接将永远不会被重用。
 * </p>
 * 
 * <p>
 * As the ProxyToServerConnections receive responses from their servers, they
 * feed these back to the client by calling
 * {@link #respond(ProxyToServerConnection, HttpFilters, HttpRequest, HttpResponse, HttpObject)}
 *
 * 当ProxyToServerConnections从其服务器接收响应时，
 * 它们通过调用{@link #respond（ProxyToServerConnection，HttpFilters，HttpRequest，HttpResponse，HttpObject）}将其反馈给客户端
 * .
 * </p>
 */
public class ClientToProxyConnection extends ProxyConnection<HttpRequest> {
    private static final HttpResponseStatus CONNECTION_ESTABLISHED = new HttpResponseStatus(
            200, "Connection established");
    /**
     * Used for case-insensitive comparisons when parsing Connection header values.
     * 解析连接头值时，用于不区分大小写的比较
     */
    private static final String LOWERCASE_TRANSFER_ENCODING_HEADER = HttpHeaders.Names.TRANSFER_ENCODING.toLowerCase(Locale.US);

    /**
     * Used for case-insensitive comparisons when checking direct proxy request.
     * 检查直接代理请求时，用于不区分大小写的比较。
     */
    private static final Pattern HTTP_SCHEME = Pattern.compile("^http://.*", Pattern.CASE_INSENSITIVE);

    /**
     * Keep track of all ProxyToServerConnections by host+port.
     * 通过主机+端口跟踪所有ProxyToServerConnections。
     */
    private final Map<String, ProxyToServerConnection> serverConnectionsByHostAndPort = new ConcurrentHashMap<String, ProxyToServerConnection>();

    /**
     * Keep track of how many servers are currently in the process of
     * connecting.
     * 跟踪连接过程中当前有多少服务器
     */
    private final AtomicInteger numberOfCurrentlyConnectingServers = new AtomicInteger(
            0);

    /**
     * Keep track of how many servers are currently connected.
     * 跟踪当前连接了多少台服务器
     */
    private final AtomicInteger numberOfCurrentlyConnectedServers = new AtomicInteger(
            0);

    /**
     * Keep track of how many times we were able to reuse a connection.
     * 跟踪我们能够重用连接的次数
     */
    private final AtomicInteger numberOfReusedServerConnections = new AtomicInteger(
            0);

    /**
     * This is the current server connection that we're using while transferring
     * chunked data.
     * 这是我们在传输分块数据时使用的当前服务器连接
     */
    private volatile ProxyToServerConnection currentServerConnection;

    /**
     * The current filters to apply to incoming requests/chunks.
     * 当前适用于传入请求/块的过滤器
     */
    private volatile HttpFilters currentFilters = HttpFiltersAdapter.NOOP_FILTER;

    private volatile SSLSession clientSslSession;

    /**
     * Tracks whether or not this ClientToProxyConnection is current doing MITM.
     * 跟踪此ClientToProxyConnection是否当前正在执行MITM。
     */
    private volatile boolean mitming = false;

    private AtomicBoolean authenticated = new AtomicBoolean();

    private final GlobalTrafficShapingHandler globalTrafficShapingHandler;

    /**
     * The current HTTP request that this connection is currently servicing.
     * 该连接当前正在服务的当前HTTP请求
     */
    private volatile HttpRequest currentRequest;

    ClientToProxyConnection(
            final DefaultHttpProxyServer proxyServer,
            SslEngineSource sslEngineSource,
            boolean authenticateClients,
            ChannelPipeline pipeline,
            GlobalTrafficShapingHandler globalTrafficShapingHandler) {
        super(AWAITING_INITIAL, proxyServer, false);

        initChannelPipeline(pipeline);

        if (sslEngineSource != null) {
            LOG.debug("Enabling encryption of traffic from client to proxy");
            encrypt(pipeline, sslEngineSource.newSslEngine(),
                    authenticateClients)
                    .addListener(
                            new GenericFutureListener<Future<? super Channel>>() {
                                @Override
                                public void operationComplete(
                                        Future<? super Channel> future)
                                        throws Exception {
                                    if (future.isSuccess()) {
                                        clientSslSession = sslEngine
                                                .getSession();
                                        recordClientSSLHandshakeSucceeded();
                                    }
                                }
                            });
        }
        this.globalTrafficShapingHandler = globalTrafficShapingHandler;

        LOG.debug("Created ClientToProxyConnection");
    }

    /***************************************************************************
     * Reading
     **************************************************************************/

    @Override
    protected ConnectionState readHTTPInitial(HttpRequest httpRequest) {
        LOG.debug("Received raw request: {}", httpRequest);

        // if we cannot parse the request, immediately return a 400 and close the connection, since we do not know what state
        // the client thinks the connection is in
        /*
          如果我们无法解析请求，请立即返回400并关闭连接，因为我们不知道客户端认为连接处于什么状态
         */
        if (httpRequest.getDecoderResult().isFailure()) {
            LOG.debug("Could not parse request from client. Decoder result: {}", httpRequest.getDecoderResult().toString());

            FullHttpResponse response = ProxyUtils.createFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.BAD_REQUEST,
                    "Unable to parse HTTP request");
            HttpHeaders.setKeepAlive(response, false);

            respondWithShortCircuitResponse(response);

            return DISCONNECT_REQUESTED;
        }

        boolean authenticationRequired = authenticationRequired(httpRequest);

        if (authenticationRequired) {
            //未认证
            LOG.debug("Not authenticated!!");
            return AWAITING_PROXY_AUTHENTICATION;
        } else {
            return doReadHTTPInitial(httpRequest);
        }
    }

    /**
     * <p>
     * Reads an {@link HttpRequest}.
     * 读取{@link HttpRequest}。
     * </p>
     * 
     * <p>
     * If we don't yet have a {@link ProxyToServerConnection} for the desired
     * server, this takes care of creating it.
     * 如果我们还没有想要的服务器的{@link ProxyToServerConnection}，则需要创建它。
     * </p>
     * 
     * <p>
     * Note - the "server" could be a chained proxy, not the final endpoint for
     * the request.
     * 注意-“服务器”可以是链接的代理，而不是请求的最终端点
     * </p>
     * 
     * @param httpRequest
     * @return
     */
    private ConnectionState doReadHTTPInitial(HttpRequest httpRequest) {
        // Make a copy of the original request
        // 复制原始请求
        this.currentRequest = copy(httpRequest);

        // Set up our filters based on the original request. If the HttpFiltersSource returns null (meaning the request/response
        // should not be filtered), fall back to the default no-op filter source.
        // 根据原始请求设置过滤器。如果HttpFiltersSource返回null（意味着不应过滤请求/响应），请退回到默认的无操作过滤器源
        HttpFilters filterInstance = proxyServer.getFiltersSource().filterRequest(currentRequest, ctx);
        if (filterInstance != null) {
            currentFilters = filterInstance;
        } else {
            currentFilters = HttpFiltersAdapter.NOOP_FILTER;
        }

        // Send the request through the clientToProxyRequest filter, and respond with the short-circuit response if required

        //通过clientToProxyRequest过滤器发送请求，并在需要时回复短路响应

        /**************************************************************/
        /*                  clientToProxyRequest                      */
        /**************************************************************/
        HttpResponse clientToProxyFilterResponse = currentFilters.clientToProxyRequest(httpRequest);

        if (clientToProxyFilterResponse != null) {
            // 用来自滤波器的短路响应来响应客户
            LOG.debug("Responding to client with short-circuit response from filter: {}", clientToProxyFilterResponse);

            boolean keepAlive = respondWithShortCircuitResponse(clientToProxyFilterResponse);
            if (keepAlive) {
                return AWAITING_INITIAL;
            } else {
                return DISCONNECT_REQUESTED;
            }
        }

        // if origin-form requests are not explicitly enabled, short-circuit requests that treat the proxy as the
        // origin server, to avoid infinite loops
        //如果未显式启用原始形式的请求，则将代理视为原始服务器的短路请求可避免无限循环
        if (!proxyServer.isAllowRequestsToOriginServer() && isRequestToOriginServer(httpRequest)) {
            boolean keepAlive = writeBadRequest(httpRequest);
            if (keepAlive) {
                return AWAITING_INITIAL;
            } else {
                return DISCONNECT_REQUESTED;
            }
        }

        // Identify our server and chained proxy
        // 识别我们的服务器和链接代理
        String serverHostAndPort = identifyHostAndPort(httpRequest);

        LOG.debug("Ensuring that hostAndPort are available in {}",
                httpRequest.getUri());
        if (serverHostAndPort == null || StringUtils.isBlank(serverHostAndPort)) {
            LOG.warn("No host and port found in {}", httpRequest.getUri());
            boolean keepAlive = writeBadGateway(httpRequest);
            if (keepAlive) {
                return AWAITING_INITIAL;
            } else {
                return DISCONNECT_REQUESTED;
            }
        }

        LOG.debug("Finding ProxyToServerConnection for: {}", serverHostAndPort);
        currentServerConnection = isMitming() || isTunneling() ?
                this.currentServerConnection
                : this.serverConnectionsByHostAndPort.get(serverHostAndPort);

        boolean newConnectionRequired = false;
        if (ProxyUtils.isCONNECT(httpRequest)) {
            //不重用现有的ProxyToServerConnection，因为请求是的CONNECT
            LOG.debug(
                    "Not reusing existing ProxyToServerConnection because request is a CONNECT for: {}",
                    serverHostAndPort);
            newConnectionRequired = true;
        } else if (currentServerConnection == null) {
            // 找不到适用于的现有ProxyToServerConnection
            LOG.debug("Didn't find existing ProxyToServerConnection for: {}",
                    serverHostAndPort);
            newConnectionRequired = true;
        }

        if (newConnectionRequired) {
            try {
                currentServerConnection = ProxyToServerConnection.create(
                        proxyServer,
                        this,
                        serverHostAndPort,
                        currentFilters,
                        httpRequest,
                        globalTrafficShapingHandler);
                if (currentServerConnection == null) {
                    // 无法创建服务器连接，可能没有可用的链接代理
                    LOG.debug("Unable to create server connection, probably no chained proxies available");
                    boolean keepAlive = writeBadGateway(httpRequest);
                    resumeReading();
                    if (keepAlive) {
                        return AWAITING_INITIAL;
                    } else {
                        return DISCONNECT_REQUESTED;
                    }
                }
                // Remember the connection for later
                // 记住连接以便以后使用
                serverConnectionsByHostAndPort.put(serverHostAndPort,
                        currentServerConnection);
            } catch (UnknownHostException uhe) {
                LOG.info("Bad Host {}", httpRequest.getUri());
                boolean keepAlive = writeBadGateway(httpRequest);
                resumeReading();
                if (keepAlive) {
                    return AWAITING_INITIAL;
                } else {
                    return DISCONNECT_REQUESTED;
                }
            }
        } else {
            // 重用现有服务器连接
            LOG.debug("Reusing existing server connection: {}",
                    currentServerConnection);
            numberOfReusedServerConnections.incrementAndGet();
        }

        modifyRequestHeadersToReflectProxying(httpRequest);

        /**************************************************************/
        /*                  proxyToServerRequest                     */
        /**************************************************************/

        HttpResponse proxyToServerFilterResponse = currentFilters.proxyToServerRequest(httpRequest);
        if (proxyToServerFilterResponse != null) {
            // 用来自滤波器的短路响应来响应客户
            LOG.debug("Responding to client with short-circuit response from filter: {}", proxyToServerFilterResponse);

            boolean keepAlive = respondWithShortCircuitResponse(proxyToServerFilterResponse);
            if (keepAlive) {
                return AWAITING_INITIAL;
            } else {
                return DISCONNECT_REQUESTED;
            }
        }

        // 向ProxyToServerConnection写入请求
        LOG.debug("Writing request to ProxyToServerConnection");
        currentServerConnection.write(httpRequest, currentFilters);

        // Figure out our next state
        // 弄清楚我们的下一个状态
        if (ProxyUtils.isCONNECT(httpRequest)) {
            return NEGOTIATING_CONNECT;
        } else if (ProxyUtils.isChunked(httpRequest)) {
            return AWAITING_CHUNK;
        } else {
            return AWAITING_INITIAL;
        }
    }

    /**
     * Returns true if the specified request is a request to an origin server, rather than to a proxy server. If this
     * request is being MITM'd, this method always returns false. The format of requests to a proxy server are defined
     * in RFC 7230, section 5.3.2 (all other requests are considered requests to an origin server):
     *
     * 如果指定的请求是对原始服务器的请求，而不是对代理服务器的请求，则返回true。
     * 如果此请求正在进行MITM处理，则此方法始终返回false。
     * 对代理服务器的请求格式在RFC 7230第5.3.2节中定义（所有其他请求均视为对原始服务器的请求）：
     <pre>
         When making a request to a proxy, other than a CONNECT or server-wide
         OPTIONS request (as detailed below), a client MUST send the target
         URI in absolute-form as the request-target.
         向代理（而不是CONNECT或服务器范围的代理）发出请求时
         OPTIONS请求（如下所述），客户端必须发送目标URI以绝对形式作为请求目标
         [...]
         An example absolute-form of request-line would be:
         GET http://www.example.org/pub/WWW/TheProject.html HTTP/1.1
         To allow for transition to the absolute-form for all requests in some
         future version of HTTP, a server MUST accept the absolute-form in
         requests, even though HTTP/1.1 clients will only send them in
         requests to proxies.
         为了允许在将来的HTTP版本中转换为所有请求的绝对形式，
         即使HTTP / 1.1客户端仅将请求中的绝对形式发送给代理，服务器也必须接受请求中的绝对形式
     </pre>
     *
     * @param httpRequest the request to evaluate
     * @return true if the specified request is a request to an origin server, otherwise false
     *        如果指定的请求是对原始服务器的请求，则为true，否则为false
     */
    private boolean isRequestToOriginServer(HttpRequest httpRequest) {
        // while MITMing, all HTTPS requests are requests to the origin server, since the client does not know
        // the request is being MITM'd by the proxy
        // 在进行MITMing时，所有HTTPS请求都是对原始服务器的请求，因为客户端不知道该请求正在由代理进行MITM处理
        if (httpRequest.getMethod() == HttpMethod.CONNECT || isMitming()) {
            return false;
        }

        // direct requests to the proxy have the path only without a scheme
        // 对代理的直接请求仅具有路径而没有方案
        String uri = httpRequest.getUri();
        return !HTTP_SCHEME.matcher(uri).matches();
    }

    @Override
    protected void readHTTPChunk(HttpContent chunk) {

        /**************************************************************/
        /*                  clientToProxyRequest                      */
        /**************************************************************/
        currentFilters.clientToProxyRequest(chunk);

        /**************************************************************/
        /*                  proxyToServerRequest                      */
        /**************************************************************/
        currentFilters.proxyToServerRequest(chunk);

        currentServerConnection.write(chunk);
    }

    @Override
    protected void readRaw(ByteBuf buf) {
        currentServerConnection.write(buf);
    }

    /***************************************************************************
     * Writing
     **************************************************************************/

    /**
     * Send a response to the client.
     * 向客户发送回复
     * 
     * @param serverConnection
     *            the ProxyToServerConnection that's responding
     *            正在响应的ProxyToServerConnection
     * @param filters
     *            the filters to apply to the response
     *            应用于响应的过滤器
     * @param currentHttpRequest
     *            the HttpRequest that prompted this response
     *            提示此响应的HttpReques
     * @param currentHttpResponse
     *            the HttpResponse corresponding to this data (when doing
     *            chunked transfers, this is the initial HttpResponse object
     *            that came in before the other chunks)
     *            与此数据相对应的HttpResponse（在进行分块传输时，这是最初的HttpResponse对象，它在其他块之前出现）
     * @param httpObject
     *            the data with which to respond
     *            响应数据
     */
    void respond(ProxyToServerConnection serverConnection, HttpFilters filters,
            HttpRequest currentHttpRequest, HttpResponse currentHttpResponse,
            HttpObject httpObject) {
        // we are sending a response to the client, so we are done handling this request
        // 我们正在向客户端发送响应，因此我们已经完成了处理此请求的操作
        this.currentRequest = null;

        /**************************************************************/
        /*                  serverToProxyResponse                      */
        /**************************************************************/
        httpObject = filters.serverToProxyResponse(httpObject);
        if (httpObject == null) {
            forceDisconnect(serverConnection);
            return;
        }

        if (httpObject instanceof HttpResponse) {
            HttpResponse httpResponse = (HttpResponse) httpObject;

            // if this HttpResponse does not have any means of signaling the end of the message body other than closing
            // the connection, convert the message to a "Transfer-Encoding: chunked" HTTP response. This avoids the need
            // to close the client connection to indicate the end of the message. (Responses to HEAD requests "must be" empty.)
            /*
              如果此HttpResponse除了关闭连接之外没有任何其他方式来通知消息主体的末尾，请将消息转换为“ Transfer-Encoding：chunked” HTTP响应。
              这样避免了关闭客户端连接以指示消息结束的需要。 （对HEAD请求的响应“必须”为空。）
             */
            if (!ProxyUtils.isHEAD(currentHttpRequest) && !ProxyUtils.isResponseSelfTerminating(httpResponse)) {
                // if this is not a FullHttpResponse,  duplicate the HttpResponse from the server before sending it to
                // the client. this allows us to set the Transfer-Encoding to chunked without interfering with netty's
                // handling of the response from the server. if we modify the original HttpResponse from the server,
                // netty will not generate the appropriate LastHttpContent when it detects the connection closure from
                // the server (see HttpObjectDecoder#decodeLast). (This does not apply to FullHttpResponses, for which
                // netty already generates the empty final chunk when Transfer-Encoding is chunked.)
                /*
                  如果这不是FullHttpResponse，请在将其发送到客户端之前，从服务器复制HttpResponse。
                  这使我们可以将Transfer-Encoding设置为分块，而不会影响netty对服务器响应的处理。
                  如果我们从服务器修改了原始的HttpResponse，
                  当netty从服务器检测到连接关闭时，netty将不会生成适当的LastHttpContent（请参阅HttpObjectDecoder＃decodeLast）。
                  （这不适用于FullHttpResponses，当对Transfer-Encoding进行分块时，netty已经为此生成了空的最终块。）
                 */
                if (!(httpResponse instanceof FullHttpResponse)) {
                    HttpResponse duplicateResponse = ProxyUtils.duplicateHttpResponse(httpResponse);

                    // set the httpObject and httpResponse to the duplicated response, to allow all other standard processing
                    // (filtering, header modification for proxying, etc.) to be applied.
                    /*
                      将httpObject和httpResponse设置为重复的响应，以允许应用所有其他标准处理（过滤，代理的标头修改等）
                     */
                    httpObject = httpResponse = duplicateResponse;
                }

                HttpHeaders.setTransferEncodingChunked(httpResponse);
            }

            fixHttpVersionHeaderIfNecessary(httpResponse);
            modifyResponseHeadersToReflectProxying(httpResponse);
        }

        /**************************************************************/
        /*                  proxyToClientResponse                      */
        /**************************************************************/

        httpObject = filters.proxyToClientResponse(httpObject);
        if (httpObject == null) {
            forceDisconnect(serverConnection);
            return;
        }

        write(httpObject);

        if (ProxyUtils.isLastChunk(httpObject)) {
            writeEmptyBuffer();
        }

        closeConnectionsAfterWriteIfNecessary(serverConnection,
                currentHttpRequest, currentHttpResponse, httpObject);
    }

    /***************************************************************************
     * Connection Lifecycle
     **************************************************************************/

    /**
     * Tells the Client that its HTTP CONNECT request was successful.
     * 告诉客户端其HTTP CONNECT请求成功。
     */
    ConnectionFlowStep RespondCONNECTSuccessful = new ConnectionFlowStep(
            this, NEGOTIATING_CONNECT) {
        @Override
        boolean shouldSuppressInitialRequest() {
            return true;
        }

        protected Future<?> execute() {
            LOG.debug("Responding with CONNECT successful");
            HttpResponse response = ProxyUtils.createFullHttpResponse(HttpVersion.HTTP_1_1,
                    CONNECTION_ESTABLISHED);
            response.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
            ProxyUtils.addVia(response, proxyServer.getProxyAlias());
            return writeToChannel(response);
        };
    };

    /**
     * On connect of the client, start waiting for an initial
     * 在客户端连接后，开始等待初始
     * {@link HttpRequest}.
     */
    @Override
    protected void connected() {
        super.connected();
        become(AWAITING_INITIAL);
        recordClientConnected();
    }

    void timedOut(ProxyToServerConnection serverConnection) {
        if (currentServerConnection == serverConnection && this.lastReadTime > currentServerConnection.lastReadTime) {
            // the idle timeout fired on the active server connection. send a timeout response to the client.
            // 活动服务器连接上触发了空闲超时。向客户端发送超时响应
            LOG.warn("Server timed out: {}", currentServerConnection);
            /**************************************************************/
            /*                  serverToProxyResponseTimedOut             */
            /**************************************************************/
            currentFilters.serverToProxyResponseTimedOut();
            writeGatewayTimeout(currentRequest);
        }
    }

    @Override
    protected void timedOut() {
        // idle timeout fired on the client channel. if we aren't waiting on a response from a server, hang up
        // 客户端通道上触发了空闲超时。如果我们不等待服务器的响应，请挂断电话
        if (currentServerConnection == null || this.lastReadTime <= currentServerConnection.lastReadTime) {
            super.timedOut();
        }
    }

    /**
     * On disconnect of the client, disconnect all server connections.
     * 断开客户端连接后，断开所有服务器连接。
     */
    @Override
    protected void disconnected() {
        super.disconnected();
        for (ProxyToServerConnection serverConnection : serverConnectionsByHostAndPort
                .values()) {
            serverConnection.disconnect();
        }
        recordClientDisconnected();
    }

    /**
     * Called when {@link ProxyToServerConnection} starts its connection flow.
     * 在{@link ProxyToServerConnection}开始其连接流程时调用
     * 
     * @param serverConnection
     */
    protected void serverConnectionFlowStarted(
            ProxyToServerConnection serverConnection) {
        stopReading();
        this.numberOfCurrentlyConnectingServers.incrementAndGet();
    }

    /**
     * If the {@link ProxyToServerConnection} completes its connection lifecycle
     * successfully, this method is called to let us know about it.
     * 如果{@link ProxyToServerConnection}成功完成其连接生命周期，则调用此方法以告知我们
     * 
     * @param serverConnection
     * @param shouldForwardInitialRequest
     */
    protected void serverConnectionSucceeded(
            ProxyToServerConnection serverConnection,
            boolean shouldForwardInitialRequest) {
        LOG.debug("Connection to server succeeded: {}",
                serverConnection.getRemoteAddress());
        resumeReadingIfNecessary();
        become(shouldForwardInitialRequest ? getCurrentState()
                : AWAITING_INITIAL);
        numberOfCurrentlyConnectedServers.incrementAndGet();
    }

    /**
     * If the {@link ProxyToServerConnection} fails to complete its connection
     * lifecycle successfully, this method is called to let us know about it.
     *
     * 如果{@link ProxyToServerConnection}无法成功完成其连接生命周期，则将调用此方法以通知我们
     * 
     * <p>
     * After failing to connect to the server, one of two things can happen:
     * 连接服务器失败后，可能会发生以下两种情况之一：
     * </p>
     * 
     * <ol>
     * <li>If the server was a chained proxy, we fall back to connecting to the
     * ultimate endpoint directly.</li>
     * 如果服务器是链接的代理，我们将退回到直接连接到终极端点的方式
     * <li>If the server was the ultimate endpoint, we return a 502 Bad Gateway
     * to the client.</li>
     * 如果服务器是最终端点，我们将502 Bad Gateway 返回给客户端
     * </ol>
     * 
     * @param serverConnection
     * @param lastStateBeforeFailure
     * @param cause
     *            what caused the failure
     * 
     * @return true if we're falling back to a another chained proxy (or direct
     *         connection) and trying again
     *         如果我们退回到另一个链接代理（或直接连接）并重试，则为true
     */
    protected boolean serverConnectionFailed(
            ProxyToServerConnection serverConnection,
            ConnectionState lastStateBeforeFailure,
            Throwable cause) {
        resumeReadingIfNecessary();
        HttpRequest initialRequest = serverConnection.getInitialRequest();
        try {
            boolean retrying = serverConnection.connectionFailed(cause);
            if (retrying) {
                // 无法连接到上游服务器或链接的代理。正在重试连接。失败前的最后状态
                LOG.debug("Failed to connect to upstream server or chained proxy. Retrying connection. Last state before failure: {}",
                        lastStateBeforeFailure, cause);
                return true;
            } else {
                // 与上游服务器或链接代理的连接失败
                LOG.debug(
                        "Connection to upstream server or chained proxy failed: {}.  Last state before failure: {}",
                        serverConnection.getRemoteAddress(),
                        lastStateBeforeFailure,
                        cause);
                connectionFailedUnrecoverably(initialRequest, serverConnection);
                return false;
            }
        } catch (UnknownHostException uhe) {
            connectionFailedUnrecoverably(initialRequest, serverConnection);
            return false;
        }
    }

    private void connectionFailedUnrecoverably(HttpRequest initialRequest, ProxyToServerConnection serverConnection) {
        // the connection to the server failed, so disconnect the server and remove the ProxyToServerConnection from the
        // map of open server connections
        // 与服务器的连接失败，因此断开服务器连接，并从打开的服务器连接的映射中删除ProxyToServerConnection
        serverConnection.disconnect();
        this.serverConnectionsByHostAndPort.remove(serverConnection.getServerHostAndPort());

        boolean keepAlive = writeBadGateway(initialRequest);
        if (keepAlive) {
            become(AWAITING_INITIAL);
        } else {
            become(DISCONNECT_REQUESTED);
        }
    }

    private void resumeReadingIfNecessary() {
        if (this.numberOfCurrentlyConnectingServers.decrementAndGet() == 0) {
            LOG.debug("All servers have finished attempting to connect, resuming reading from client.");
            resumeReading();
        }
    }

    /***************************************************************************
     * Other Lifecycle
     **************************************************************************/

    /**
     * On disconnect of the server, track that we have one fewer connected
     * servers and then disconnect the client if necessary.
     * 
     * @param serverConnection
     */
    protected void serverDisconnected(ProxyToServerConnection serverConnection) {
        numberOfCurrentlyConnectedServers.decrementAndGet();

        // for non-SSL connections, do not disconnect the client from the proxy, even if this was the last server connection.
        // this allows clients to continue to use the open connection to the proxy to make future requests. for SSL
        // connections, whether we are tunneling or MITMing, we need to disconnect the client because there is always
        // exactly one ClientToProxyConnection per ProxyToServerConnection, and vice versa.
        if (isTunneling() || isMitming()) {
            disconnect();
        }
    }

    /**
     * When the ClientToProxyConnection becomes saturated, stop reading on all
     * associated ProxyToServerConnections.
     */
    @Override
    synchronized protected void becameSaturated() {
        super.becameSaturated();
        for (ProxyToServerConnection serverConnection : serverConnectionsByHostAndPort
                .values()) {
            synchronized (serverConnection) {
                if (this.isSaturated()) {
                    serverConnection.stopReading();
                }
            }
        }
    }

    /**
     * When the ClientToProxyConnection becomes writable, resume reading on all
     * associated ProxyToServerConnections.
     */
    @Override
    synchronized protected void becameWritable() {
        super.becameWritable();
        for (ProxyToServerConnection serverConnection : serverConnectionsByHostAndPort
                .values()) {
            synchronized (serverConnection) {
                if (!this.isSaturated()) {
                    serverConnection.resumeReading();
                }
            }
        }
    }

    /**
     * When a server becomes saturated, we stop reading from the client.
     * 
     * @param serverConnection
     */
    synchronized protected void serverBecameSaturated(
            ProxyToServerConnection serverConnection) {
        if (serverConnection.isSaturated()) {
            LOG.info("Connection to server became saturated, stopping reading");
            stopReading();
        }
    }

    /**
     * When a server becomes writeable, we check to see if all servers are
     * writeable and if they are, we resume reading.
     * 
     * @param serverConnection
     */
    synchronized protected void serverBecameWriteable(
            ProxyToServerConnection serverConnection) {
        boolean anyServersSaturated = false;
        for (ProxyToServerConnection otherServerConnection : serverConnectionsByHostAndPort
                .values()) {
            if (otherServerConnection.isSaturated()) {
                anyServersSaturated = true;
                break;
            }
        }
        if (!anyServersSaturated) {
            LOG.info("All server connections writeable, resuming reading");
            resumeReading();
        }
    }

    @Override
    protected void exceptionCaught(Throwable cause) {
        try {
            if (cause instanceof IOException) {
                // IOExceptions are expected errors, for example when a browser is killed and aborts a connection.
                // rather than flood the logs with stack traces for these expected exceptions, we log the message at the
                // INFO level and the stack trace at the DEBUG level.
                // IOException是预期的错误，例如，当浏览器被杀死并中止连接时
                // 而不是使用这些预期异常的堆栈跟踪来填充日志，我们将消息记录在INFO级别，并将堆栈跟踪记录在DEBUG级别。
                LOG.info("An IOException occurred on ClientToProxyConnection: " + cause.getMessage());
                LOG.debug("An IOException occurred on ClientToProxyConnection", cause);
            } else if (cause instanceof RejectedExecutionException) {
                LOG.info("An executor rejected a read or write operation on the ClientToProxyConnection (this is normal if the proxy is shutting down). Message: " + cause.getMessage());
                LOG.debug("A RejectedExecutionException occurred on ClientToProxyConnection", cause);
            } else {
                LOG.error("Caught an exception on ClientToProxyConnection", cause);
            }
        } finally {
            // always disconnect the client when an exception occurs on the channel
            disconnect();
        }
    }

    /***************************************************************************
     * Connection Management
     **************************************************************************/

    /**
     * Initialize the {@link ChannelPipeline} for the client to proxy channel.
     * LittleProxy acts like a server here.
     * 初始化{@link ChannelPipeline}，以便客户端代理频道。
     * LittleProxy在这里像服务器一样
     * 
     * A {@link ChannelPipeline} invokes the read (Inbound) handlers in
     * ascending ordering of the list and then the write (Outbound) handlers in
     * descending ordering.
     *
     * {@link ChannelPipeline}以列表的升序调用读（入站）处理程序，然后以降序调用写（出站）处理程序。
     * 
     * Regarding the Javadoc of {@link HttpObjectAggregator} it's needed to have
     * the {@link HttpResponseEncoder} or {@link io.netty.handler.codec.http.HttpRequestEncoder} before the
     * {@link HttpObjectAggregator} in the {@link ChannelPipeline}.
     * 关于{@link HttpObjectAggregator}的Javadoc，
     * 需要在{@link ChannelPipeline 中的{@link HttpObjectAggregator}
     * 之前有一个{@link HttpResponseEncoder}或{@link io.netty.handler.codec.http.HttpRequestEncoder}。
     * 
     * @param pipeline
     */
    private void initChannelPipeline(ChannelPipeline pipeline) {
        LOG.debug("Configuring ChannelPipeline");

        pipeline.addLast("bytesReadMonitor", bytesReadMonitor);
        pipeline.addLast("bytesWrittenMonitor", bytesWrittenMonitor);

        pipeline.addLast("encoder", new HttpResponseEncoder());
        // We want to allow longer request lines, headers, and chunks
        // respectively.
        pipeline.addLast("decoder", new HttpRequestDecoder(
                proxyServer.getMaxInitialLineLength(),
                proxyServer.getMaxHeaderSize(),
                proxyServer.getMaxChunkSize()));

        // Enable aggregation for filtering if necessary
        // 启用聚合以进行过滤（如有必要）
        int numberOfBytesToBuffer = proxyServer.getFiltersSource()
                .getMaximumRequestBufferSizeInBytes();
        if (numberOfBytesToBuffer > 0) {
            aggregateContentForFiltering(pipeline, numberOfBytesToBuffer);
        }

        pipeline.addLast("requestReadMonitor", requestReadMonitor);
        pipeline.addLast("responseWrittenMonitor", responseWrittenMonitor);

        pipeline.addLast(
                "idle",
                new IdleStateHandler(0, 0, proxyServer
                        .getIdleConnectionTimeout()));

        pipeline.addLast("handler", this);
    }

    /**
     * This method takes care of closing client to proxy and/or proxy to server
     * connections after finishing a write.
     * 此方法负责在完成写操作后关闭客户端到代理的代理和/或代理到服务器的连接。
     */
    private void closeConnectionsAfterWriteIfNecessary(
            ProxyToServerConnection serverConnection,
            HttpRequest currentHttpRequest, HttpResponse currentHttpResponse,
            HttpObject httpObject) {
        boolean closeServerConnection = shouldCloseServerConnection(
                currentHttpRequest, currentHttpResponse, httpObject);
        boolean closeClientConnection = shouldCloseClientConnection(
                currentHttpRequest, currentHttpResponse, httpObject);

        if (closeServerConnection) {
            LOG.debug("Closing remote connection after writing to client");
            serverConnection.disconnect();
        }

        if (closeClientConnection) {
            LOG.debug("Closing connection to client after writes");
            disconnect();
        }
    }

    private void forceDisconnect(ProxyToServerConnection serverConnection) {
        LOG.debug("Forcing disconnect");
        serverConnection.disconnect();
        disconnect();
    }

    /**
     * Determine whether or not the client connection should be closed.
     * 确定是否应关闭客户端连接。
     * 
     * @param req
     * @param res
     * @param httpObject
     * @return
     */
    private boolean shouldCloseClientConnection(HttpRequest req,
            HttpResponse res, HttpObject httpObject) {
        if (ProxyUtils.isChunked(res)) {
            // If the response is chunked, we want to return false unless it's
            // the last chunk. If it is the last chunk, then we want to pass
            // through to the same close semantics we'd otherwise use.
            /*
              如果响应是分块的，除非它是最后一块，否则我们要返回false。
              如果它是最后一个块，那么我们想传递给我们将要使用的相同的封闭语义。
             */
            if (httpObject != null) {
                if (!ProxyUtils.isLastChunk(httpObject)) {
                    String uri = null;
                    if (req != null) {
                        uri = req.getUri();
                    }
                    // 不关闭中间块的客户端连接
                    LOG.debug("Not closing client connection on middle chunk for {}", uri);
                    return false;
                } else {
                    // 处理最后一块。使用正常的客户端连接关闭规则
                    LOG.debug("Handling last chunk. Using normal client connection closing rules.");
                }
            }
        }

        if (!HttpHeaders.isKeepAlive(req)) {
            LOG.debug("Closing client connection since request is not keep alive: {}", req);
            // Here we simply want to close the connection because the
            // client itself has requested it be closed in the request.
            // 在这里，我们只是想关闭连接，因为客户端本身已请求在请求中将其关闭。
            return true;
        }

        // ignore the response's keep-alive; we can keep this client connection open as long as the client allows it.
        // 忽略响应保持活动；只要客户端允许，我们就可以保持此客户端连接的打开状态

        // 不关闭客户端连接以进行请求
        LOG.debug("Not closing client connection for request: {}", req);
        return false;
    }

  /**
   * Determines if the remote connection should be closed based on the request and response pair. If
   * the request is HTTP 1.0 with no keep-alive header, for example, the connection should be
   * closed.
   *
   * <p>根据请求和响应对确定是否应关闭远程连接。例如，如果请求是不带保持活动报头的HTTP 1.0，则应关闭连接
   *
   * <p>This in part determines if we should close the connection. Here's the relevant section of
   * RFC 2616: 这部分决定了我们是否应该关闭连接。这是RFC 2616的相关部分
   *
   * <p>"HTTP/1.1 defines the "close" connection option for the sender to signal that the connection
   * will be closed after completion of the response. For example, HTTP /
   * 1.1定义了发送者的“关闭”连接选项，以指示在响应完成后将关闭连接。例如，
   *
   * <p>Connection: close
   *
   * <p>in either the request or the response header fields indicates that the connection SHOULD NOT
   * be considered `persistent' (section 8.1) after the current request/response is complete."
   *
   * 在请求或响应标头字段中的表示 当前请求/响应完成后，不应将连接视为“持久”（第8.1节）。”
   *
   * @param req The request.
   * @param res The response.
   * @param msg The message.
   * @return Returns true if the connection should close.
   */
  private boolean shouldCloseServerConnection(HttpRequest req, HttpResponse res, HttpObject msg) {
        if (ProxyUtils.isChunked(res)) {
            // If the response is chunked, we want to return false unless it's
            // the last chunk. If it is the last chunk, then we want to pass
            // through to the same close semantics we'd otherwise use.
            // 如果响应是分块的，除非它是最后一块，否则我们要返回false。 如果它是最后一个块，那么我们想传递给我们将要使用的相同的封闭语义。
            if (msg != null) {
                if (!ProxyUtils.isLastChunk(msg)) {
                    String uri = null;
                    if (req != null) {
                        uri = req.getUri();
                    }
                    // 不关闭中间块的服务器连接
                    LOG.debug("Not closing server connection on middle chunk for {}", uri);
                    return false;
                } else {
                    // 处理最后一块。使用正常的服务器连接关闭规则
                    LOG.debug("Handling last chunk. Using normal server connection closing rules.");
                }
            }
        }

        // ignore the request's keep-alive; we can keep this server connection open as long as the server allows it.

        // 如果响应是分块的，除非它是最后一块，否则我们要返回false。 如果它是最后一个块，那么我们想传递给我们将要使用的相同的封闭语义。
        if (!HttpHeaders.isKeepAlive(res)) {
            // 正在关闭服务器连接，因为响应无法保持活动状态
            LOG.debug("Closing server connection since response is not keep alive: {}", res);
            // In this case, we want to honor the Connection: close header
            // from the remote server and close that connection. We don't
            // necessarily want to close the connection to the client, however
            // as it's possible it has other connections open.
            /*
               在这种情况下，我们要尊重Connection：从远程服务器关闭header并关闭该连接。
               我们不一定要关闭与客户端的连接，但是有可能打开其他连接。
             */
            return true;
        }

        // 不关闭服务器连接以进行响应
        LOG.debug("Not closing server connection for response: {}", res);
        return false;
    }

    /***************************************************************************
     * Authentication
     **************************************************************************/

    /**
     * <p>
     * Checks whether the given HttpRequest requires authentication.
     * 检查给定的Http请求是否需要身份验证
     * </p>
     * 
     * <p>
     * If the request contains credentials, these are checked.
     * 如果请求中包含凭据，则将对这些凭据进行检查
     * </p>
     * 
     * <p>
     * If authentication is still required, either because no credentials were
     * provided or the credentials were wrong, this writes a 407 response to the
     * client.
     * 如果仍然需要身份验证，或者因为没有提供凭据或凭据错误，这将向客户端写入407响应。
     *
     * </p>
     * 
     * @param request
     * @return
     */
    private boolean authenticationRequired(HttpRequest request) {

        if (authenticated.get()) {
            return false;
        }

        final ProxyAuthenticator authenticator = proxyServer
                .getProxyAuthenticator();

        if (authenticator == null)
            return false;

        if (!request.headers().contains(HttpHeaders.Names.PROXY_AUTHORIZATION)) {
            writeAuthenticationRequired(authenticator.getRealm());
            return true;
        }

        List<String> values = request.headers().getAll(
                HttpHeaders.Names.PROXY_AUTHORIZATION);
        String fullValue = values.iterator().next();
        String value = StringUtils.substringAfter(fullValue, "Basic ").trim();

        byte[] decodedValue = BaseEncoding.base64().decode(value);

        String decodedString = new String(decodedValue, Charset.forName("UTF-8"));
        
        String userName = StringUtils.substringBefore(decodedString, ":");
        String password = StringUtils.substringAfter(decodedString, ":");
        if (!authenticator.authenticate(userName, password)) {
            writeAuthenticationRequired(authenticator.getRealm());
            return true;
        }

        LOG.debug("Got proxy authorization!");
        // We need to remove the header before sending the request on.
        // 我们需要先删除标头，然后再发送请求
        String authentication = request.headers().get(
                HttpHeaders.Names.PROXY_AUTHORIZATION);
        LOG.debug(authentication);
        request.headers().remove(HttpHeaders.Names.PROXY_AUTHORIZATION);
        authenticated.set(true);
        return false;
    }

    private void writeAuthenticationRequired(String realm) {
        String body = "<!DOCTYPE HTML \"-//IETF//DTD HTML 2.0//EN\">\n"
                + "<html><head>\n"
                + "<title>407 Proxy Authentication Required</title>\n"
                + "</head><body>\n"
                + "<h1>Proxy Authentication Required</h1>\n"
                + "<p>This server could not verify that you\n"
                + "are authorized to access the document\n"
                + "requested.  Either you supplied the wrong\n"
                + "credentials (e.g., bad password), or your\n"
                + "browser doesn't understand how to supply\n"
                + "the credentials required.</p>\n" + "</body></html>\n";
        FullHttpResponse response = ProxyUtils.createFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED, body);
        HttpHeaders.setDate(response, new Date());
        response.headers().set("Proxy-Authenticate",
                "Basic realm=\"" + (realm == null ? "Restricted Files" : realm) + "\"");
        write(response);
    }

    /***************************************************************************
     * Request/Response Rewriting
     **************************************************************************/

    /**
     * Copy the given {@link HttpRequest} verbatim.
     * 逐字复制给定的{@link HttpRequest}
     * 
     * @param original
     * @return
     */
    private HttpRequest copy(HttpRequest original) {
        if (original instanceof FullHttpRequest) {
            return ((FullHttpRequest) original).copy();
        } else {
            HttpRequest request = new DefaultHttpRequest(original.getProtocolVersion(),
                    original.getMethod(), original.getUri());
            request.headers().set(original.headers());
            return request;
        }
    }

    /**
     * Chunked encoding is an HTTP 1.1 feature, but sometimes we get a chunked
     * response that reports its HTTP version as 1.0. In this case, we change it
     * to 1.1.
     *
     * 块编码是HTTP 1.1的功能，但是有时我们会收到分块响应，
     * 报告其HTTP版本为1.0。在这种情况下，我们将更改为1.1。
     * 
     * @param httpResponse
     */
    private void fixHttpVersionHeaderIfNecessary(HttpResponse httpResponse) {
        String te = httpResponse.headers().get(
                HttpHeaders.Names.TRANSFER_ENCODING);
        if (StringUtils.isNotBlank(te)
                && te.equalsIgnoreCase(HttpHeaders.Values.CHUNKED)) {
            if (httpResponse.getProtocolVersion() != HttpVersion.HTTP_1_1) {
                LOG.debug("Fixing HTTP version.");
                httpResponse.setProtocolVersion(HttpVersion.HTTP_1_1);
            }
        }
    }

    /**
     * If and only if our proxy is not running in transparent mode, modify the
     * request headers to reflect that it was proxied.
     * 当且仅当我们的代理不在透明模式下运行时，修改request标头以反映它已被代理
     * 
     * @param httpRequest
     */
    private void modifyRequestHeadersToReflectProxying(HttpRequest httpRequest) {
        if (!currentServerConnection.hasUpstreamChainedProxy()) {
            /*
             * We are making the request to the origin server, so must modify
             * the 'absolute-URI' into the 'origin-form' as per RFC 7230
             * section 5.3.1.
             * 我们正在向原始服务器发出请求，
             * 因此必须根据RFC 7230的“ 5.3.1节”将“绝对URI”修改为“原始形式”
             *
             * This must happen even for 'transparent' mode, otherwise the origin
             * server could infer that the request came via a proxy server.
             * 即使对于“透明”模式，也必须发生这种情况，否则原始服务器可能会推断出请求是通过代理服务器发出的
             */
            // Modifying request for proxy chaining
            // 修改代理链接请求
            LOG.debug("Modifying request for proxy chaining");
            // Strip host from uri
            // 从uri剥离主机
            String uri = httpRequest.getUri();
            String adjustedUri = ProxyUtils.stripHost(uri);
            LOG.debug("Stripped host from uri: {}    yielding: {}", uri,
                    adjustedUri);
            httpRequest.setUri(adjustedUri);
        }
        if (!proxyServer.isTransparent()) {
            // 修改请求标头以进行代理
            LOG.debug("Modifying request headers for proxying");

            HttpHeaders headers = httpRequest.headers();

            // Remove sdch from encodings we accept since we can't decode it.
            // 从我们无法接受的编码中删除sdch，因为我们无法对其进行解码。
            ProxyUtils.removeSdchEncoding(headers);
            switchProxyConnectionHeader(headers);
            stripConnectionTokens(headers);
            stripHopByHopHeaders(headers);
            ProxyUtils.addVia(httpRequest, proxyServer.getProxyAlias());
        }
    }

    /**
     * If and only if our proxy is not running in transparent mode, modify the
     * response headers to reflect that it was proxied.
     * 当且仅当我们的代理不在透明模式下运行时，才修改响应标头以反映它已被代理。
     * 
     * @param httpResponse
     * @return
     */
    private void modifyResponseHeadersToReflectProxying(
            HttpResponse httpResponse) {
        if (!proxyServer.isTransparent()) {
            HttpHeaders headers = httpResponse.headers();

            stripConnectionTokens(headers);
            stripHopByHopHeaders(headers);
            ProxyUtils.addVia(httpResponse, proxyServer.getProxyAlias());

            /*
             * RFC2616 Section 14.18
             * 
             * A received message that does not have a Date header field MUST be
             * assigned one by the recipient if the message will be cached by
             * that recipient or gatewayed via a protocol which requires a Date.
             *
             * 如果接收者缓存该消息，或者通过需要日期的协议进行网关化，
             * 则接收者必须为接收到的没有日期头字段的消息分配一个消息。
             */
            if (!headers.contains(HttpHeaders.Names.DATE)) {
                HttpHeaders.setDate(httpResponse, new Date());
            }
        }
    }

    /**
     * Switch the de-facto standard "Proxy-Connection" header to "Connection"
     * when we pass it along to the remote host. This is largely undocumented
     * but seems to be what most browsers and servers expect.
     *
     * 当我们将其传递到远程主机时，将事实上的标准 "Proxy-Connection" 标头切换为"Connection"。
     * 这在很大程度上没有记录但似乎是大多数浏览器和服务器所期望的
     * 
     * @param headers
     *            The headers to modify
     */
    private void switchProxyConnectionHeader(HttpHeaders headers) {
        String proxyConnectionKey = "Proxy-Connection";
        if (headers.contains(proxyConnectionKey)) {
            String header = headers.get(proxyConnectionKey);
            headers.remove(proxyConnectionKey);
            headers.set(HttpHeaders.Names.CONNECTION, header);
        }
    }

  /**
   * RFC2616 Section 14.10
   *
   * <p>HTTP/1.1 proxies MUST parse the Connection header field before a message is forwarded and,
   * for each connection-token in this field, remove any header field(s) from the message with the
   * same name as the connection-token.
   * HTTP / 1.1代理必须在转发消息之前解析Connection头字段，对于该字段中的每个连接令牌，请从消息中删除与连接令牌同名的任何头字段
   *
   * @param headers The headers to modify 标头要修改
   */
  private void stripConnectionTokens(HttpHeaders headers) {
        if (headers.contains(HttpHeaders.Names.CONNECTION)) {
            for (String headerValue : headers.getAll(HttpHeaders.Names.CONNECTION)) {
                for (String connectionToken : ProxyUtils.splitCommaSeparatedHeaderValues(headerValue)) {
                    // do not strip out the Transfer-Encoding header if it is specified in the Connection header, since LittleProxy does not
                    // normally modify the Transfer-Encoding of the message.
                    /*
                     * 如果在Connection标头中指定了Transfer-Encoding标头，则不要删除它，
                     * 因为LittleProxy通常不会修改消息的Transfer-Encoding
                     */
                    if (!LOWERCASE_TRANSFER_ENCODING_HEADER.equals(connectionToken.toLowerCase(Locale.US))) {
                        headers.remove(connectionToken);
                    }
                }
            }
        }
    }

    /**
     * Removes all headers that should not be forwarded. See RFC 2616 13.5.1
     * End-to-end and Hop-by-hop Headers.
     * 删除所有不应转发的标头。参见RFC 2616 13.5.1 端到端和逐跳报头
     * 
     * @param headers
     *            The headers to modify
     */
    private void stripHopByHopHeaders(HttpHeaders headers) {
        Set<String> headerNames = headers.names();
        for (String headerName : headerNames) {
            if (ProxyUtils.shouldRemoveHopByHopHeader(headerName)) {
                headers.remove(headerName);
            }
        }
    }

    /***************************************************************************
     * Miscellaneous 杂
     **************************************************************************/

    /**
     * Tells the client that something went wrong trying to proxy its request. If the Bad Gateway is a response to
     * an HTTP HEAD request, the response will contain no body, but the Content-Length header will be set to the
     * value it would have been if this 502 Bad Gateway were in response to a GET.
     *
     * 告诉客户端尝试代理其请求时出错。
     * 如果Bad Gateway是对HTTP HEAD请求的响应，则该响应将不包含任何正文，
     * 但是Content-Length标头将设置为502 Bad Gateway响应GET时的值
     *
     * @param httpRequest the HttpRequest that is resulting in the Bad Gateway response
     * @return true if the connection will be kept open, or false if it will be disconnected
     */
    private boolean writeBadGateway(HttpRequest httpRequest) {
        String body = "Bad Gateway: " + httpRequest.getUri();
        FullHttpResponse response = ProxyUtils.createFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_GATEWAY, body);

        if (ProxyUtils.isHEAD(httpRequest)) {
            // don't allow any body content in response to a HEAD request
            response.content().clear();
        }

        return respondWithShortCircuitResponse(response);
    }

    /**
     * Tells the client that the request was malformed or erroneous. If the Bad Request is a response to
     * an HTTP HEAD request, the response will contain no body, but the Content-Length header will be set to the
     * value it would have been if this Bad Request were in response to a GET.
     * 告诉客户端请求格式错误或错误。如果错误请求是对HTTP HEAD请求的响应，则该响应将不包含任何正文，
     * 但是Content-Length标头将设置为该错误请求响应GET时的值。
     *
     * @return true if the connection will be kept open, or false if it will be disconnected
     * 如果连接保持打开状态，则为true；如果断开连接，则为false
     */
    private boolean writeBadRequest(HttpRequest httpRequest) {
        String body = "Bad Request to URI: " + httpRequest.getUri();
        FullHttpResponse response = ProxyUtils.createFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST, body);

        if (ProxyUtils.isHEAD(httpRequest)) {
            // don't allow any body content in response to a HEAD request
            // 不允许任何正文内容响应HEAD请求
            response.content().clear();
        }

        return respondWithShortCircuitResponse(response);
    }

  /**
   * Tells the client that the connection to the server, or possibly to some intermediary service
   * (such as DNS), timed out. If the Gateway Timeout is a response to an HTTP HEAD request, the
   * response will contain no body, but the Content-Length header will be set to the value it would
   * have been if this 504 Gateway Timeout were in response to a GET.
   *
   * 告诉客户端与服务器或某些中间服务（例如DNS）的连接已超时。 如果网关超时是对HTTP
   * HEAD请求的响应，则该响应将不包含任何正文，但是Content-Length标头将设置为如果此504网关超时是对GET的响应时的值。
   *
   * @param httpRequest the HttpRequest that is resulting in the Gateway Timeout response
   *                    导致网关超时响应的HttpRequest
   * @return true if the connection will be kept open, or false if it will be disconnected
   * 如果连接保持打开状态，则为true；如果断开连接，则为false
   */
  private boolean writeGatewayTimeout(HttpRequest httpRequest) {
        String body = "Gateway Timeout";
        FullHttpResponse response = ProxyUtils.createFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.GATEWAY_TIMEOUT, body);

        if (httpRequest != null && ProxyUtils.isHEAD(httpRequest)) {
            // don't allow any body content in response to a HEAD request
            // 不允许任何正文内容响应HEAD请求
            response.content().clear();
        }

        return respondWithShortCircuitResponse(response);
    }

    /**
     * Responds to the client with the specified "short-circuit" response. The response will be sent through the
     * {@link HttpFilters#proxyToClientResponse(HttpObject)} filter method before writing it to the client. The client
     * will not be disconnected, unless the response includes a "Connection: close" header, or the filter returns
     * a null HttpResponse (in which case no response will be written to the client and the connection will be
     * disconnected immediately). If the response is not a Bad Gateway or Gateway Timeout response, the response's headers
     * will be modified to reflect proxying, including adding a Via header, Date header, etc.
     *
     * 用指定的“短路”响应来响应客户端。
     * 在将响应写入客户端之前，将通过{@link HttpFilters＃proxyToClientResponse（HttpObject）}过滤器方法发送响应。
     * 除非响应包括“ Connection：close”标头，否则客户端将不会断开连接，
     * 或者过滤器返回空的HttpResponse（在这种情况下，不会将任何响应写入客户端，并且连接将立即断开连接）。
     * 如果响应不是错误的网关响应或网关超时响应，则响应的标头将被修改以反映代理，包括添加Via标头，Date标头等
     * @param httpResponse the response to return to the client
     * @return true if the connection will be kept open, or false if it will be disconnected.
     */
    private boolean respondWithShortCircuitResponse(HttpResponse httpResponse) {
        // we are sending a response to the client, so we are done handling this request
        this.currentRequest = null;
        /**************************************************************/
        /*                  proxyToClientResponse                      */
        /**************************************************************/

        HttpResponse filteredResponse = (HttpResponse) currentFilters.proxyToClientResponse(httpResponse);
        if (filteredResponse == null) {
            disconnect();
            return false;
        }

        // allow short-circuit messages to close the connection. normally the Connection header would be stripped when modifying
        // the message for proxying, so save the keep-alive status before the modifications are made.
        /*
          允许出现短路消息以关闭连接。 通常，在修改消息以进行代理时，将剥夺Connection标头，因此请在进行修改之前保存保持活动状态。
         */
        boolean isKeepAlive = HttpHeaders.isKeepAlive(httpResponse);

        // if the response is not a Bad Gateway or Gateway Timeout, modify the headers "as if" the short-circuit response were proxied
        // 如果响应不是网关或网关超时错误，则“修改”标头，就好像代理了短路响应一样
        int statusCode = httpResponse.getStatus().code();
        if (statusCode != HttpResponseStatus.BAD_GATEWAY.code() && statusCode != HttpResponseStatus.GATEWAY_TIMEOUT.code()) {
            modifyResponseHeadersToReflectProxying(httpResponse);
        }

        // restore the keep alive status, if it was overwritten when modifying headers for proxying
        // 如果修改代理标头时被覆盖，则恢复保持活动状态
        HttpHeaders.setKeepAlive(httpResponse, isKeepAlive);

        write(httpResponse);

        if (ProxyUtils.isLastChunk(httpResponse)) {
            writeEmptyBuffer();
        }

        if (!HttpHeaders.isKeepAlive(httpResponse)) {
            disconnect();
            return false;
        }

        return true;
    }

    /**
     * Identify the host and port for a request.
     * 
     * @param httpRequest
     * @return
     */
    private String identifyHostAndPort(HttpRequest httpRequest) {
        String hostAndPort = ProxyUtils.parseHostAndPort(httpRequest);
        if (StringUtils.isBlank(hostAndPort)) {
            List<String> hosts = httpRequest.headers().getAll(
                    HttpHeaders.Names.HOST);
            if (hosts != null && !hosts.isEmpty()) {
                hostAndPort = hosts.get(0);
            }
        }

        return hostAndPort;
    }

    /**
     * Write an empty buffer at the end of a chunked transfer. We need to do
     * this to handle the way Netty creates HttpChunks from responses that
     * aren't in fact chunked from the remote server using Transfer-Encoding:
     * chunked. Netty turns these into pseudo-chunked responses in cases where
     * the response would otherwise fill up too much memory or where the length
     * of the response body is unknown. This is handy because it means we can
     * start streaming response bodies back to the client without reading the
     * entire response. The problem is that in these pseudo-cases the last chunk
     * is encoded to null, and this thwarts normal ChannelFutures from
     * propagating operationComplete events on writes to appropriate channel
     * listeners. We work around this by writing an empty buffer in those cases
     * and using the empty buffer's future instead to handle any operations we
     * need to when responses are fully written back to clients.
     *
     * 在分块传输的末尾写入一个空缓冲区。 我们需要执行此操作来处理Netty从实际上不是使用Transfer-Encoding：
     * 分块从远程服务器分块的响应中创建HttpChunk的方式。 如果响应否则会填满太多内存或响应主体的长度未知，Netty会将这些转换为伪块响应。
     * 这很方便，因为这意味着我们可以开始将响应主体流式传输回客户端，而无需读取整个响应。
     * 问题在于，在这些伪情况下，最后一个块被编码为null，
     * 这妨碍了正常的ChannelFutures在向适当的通道侦听器进行写操作时传播operationComplete事件。
     * 在这种情况下，我们通过编写一个空缓冲区并使用该空缓冲区的future来解决此问题，以处理将响应完全写回到客户端时我们需要执行的所有操作
     *
     */
    private void writeEmptyBuffer() {
        write(Unpooled.EMPTY_BUFFER);
    }

    public boolean isMitming() {
        return mitming;
    }

    protected void setMitming(boolean isMitming) {
        this.mitming = isMitming;
    }

    /***************************************************************************
     * Activity Tracking/Statistics
     * 
     * We track statistics on bytes, requests and responses by adding handlers
     * at the appropriate parts of the pipeline (see initChannelPipeline()).
     **************************************************************************/
    private final BytesReadMonitor bytesReadMonitor = new BytesReadMonitor() {
        @Override
        protected void bytesRead(int numberOfBytes) {
            FlowContext flowContext = flowContext();
            for (ActivityTracker tracker : proxyServer
                    .getActivityTrackers()) {
                tracker.bytesReceivedFromClient(flowContext, numberOfBytes);
            }
        }
    };

    private RequestReadMonitor requestReadMonitor = new RequestReadMonitor() {
        @Override
        protected void requestRead(HttpRequest httpRequest) {
            FlowContext flowContext = flowContext();
            for (ActivityTracker tracker : proxyServer
                    .getActivityTrackers()) {
                tracker.requestReceivedFromClient(flowContext, httpRequest);
            }
        }
    };

    private BytesWrittenMonitor bytesWrittenMonitor = new BytesWrittenMonitor() {
        @Override
        protected void bytesWritten(int numberOfBytes) {
            FlowContext flowContext = flowContext();
            for (ActivityTracker tracker : proxyServer
                    .getActivityTrackers()) {
                tracker.bytesSentToClient(flowContext, numberOfBytes);
            }
        }
    };

    private ResponseWrittenMonitor responseWrittenMonitor = new ResponseWrittenMonitor() {
        @Override
        protected void responseWritten(HttpResponse httpResponse) {
            FlowContext flowContext = flowContext();
            for (ActivityTracker tracker : proxyServer
                    .getActivityTrackers()) {
                tracker.responseSentToClient(flowContext,
                        httpResponse);
            }
        }
    };

    private void recordClientConnected() {
        try {
            InetSocketAddress clientAddress = getClientAddress();
            for (ActivityTracker tracker : proxyServer
                    .getActivityTrackers()) {
                tracker.clientConnected(clientAddress);
            }
        } catch (Exception e) {
            LOG.error("Unable to recordClientConnected", e);
        }
    }

    private void recordClientSSLHandshakeSucceeded() {
        try {
            InetSocketAddress clientAddress = getClientAddress();
            for (ActivityTracker tracker : proxyServer
                    .getActivityTrackers()) {
                tracker.clientSSLHandshakeSucceeded(
                        clientAddress, clientSslSession);
            }
        } catch (Exception e) {
            LOG.error("Unable to recorClientSSLHandshakeSucceeded", e);
        }
    }

    private void recordClientDisconnected() {
        try {
            InetSocketAddress clientAddress = getClientAddress();
            for (ActivityTracker tracker : proxyServer
                    .getActivityTrackers()) {
                tracker.clientDisconnected(
                        clientAddress, clientSslSession);
            }
        } catch (Exception e) {
            LOG.error("Unable to recordClientDisconnected", e);
        }
    }

    public InetSocketAddress getClientAddress() {
        if (channel == null) {
            return null;
        }
        return (InetSocketAddress) channel.remoteAddress();
    }

    private FlowContext flowContext() {
        if (currentServerConnection != null) {
            return new FullFlowContext(this, currentServerConnection);
        } else {
            return new FlowContext(this);
        }
    }

}
