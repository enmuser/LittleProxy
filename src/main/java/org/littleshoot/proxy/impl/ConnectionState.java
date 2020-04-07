package org.littleshoot.proxy.impl;

enum ConnectionState {
    /**
     * Connection attempting to connect.
     * 尝试连接的连接
     */
    CONNECTING(true),

    /**
     * In the middle of doing an SSL handshake.
     * 在进行SSL握手时
     */
    HANDSHAKING(true),

    /**
     * In the process of negotiating an HTTP CONNECT from the client.
     * 在与客户端协商HTTP CONNECT的过程中
     */
    NEGOTIATING_CONNECT(true),

    /**
     * When forwarding a CONNECT to a chained proxy, we await the CONNECTION_OK
     * message from the proxy.
     *
     * 将CONNECT转发到链式代理时，我们等待来自代理的CONNECTION_OK 消息。
     */
    AWAITING_CONNECT_OK(true),

    /**
     * Connected but waiting for proxy authentication.
     * 已连接但正在等待代理身份验证
     */
    AWAITING_PROXY_AUTHENTICATION,

    /**
     * Connected and awaiting initial message (e.g. HttpRequest or
     * HttpResponse).
     * 已连接并等待初始消息（例如HttpRequest或HttpResponse）
     */
    AWAITING_INITIAL,

    /**
     * Connected and awaiting HttpContent chunk.
     * 连接并等待HttpContent块
     */
    AWAITING_CHUNK,

    /**
     * We've asked the client to disconnect, but it hasn't yet.
     * 我们已要求客户端断开连接，但尚未断开连接
     */
    DISCONNECT_REQUESTED(),

    /**
     * Disconnected
     * 断线
     */
    DISCONNECTED();

    private final boolean partOfConnectionFlow;

    ConnectionState(boolean partOfConnectionFlow) {
        this.partOfConnectionFlow = partOfConnectionFlow;
    }

    ConnectionState() {
        this(false);
    }

    /**
     * Indicates whether this ConnectionState corresponds to a step in a
     * {@link ConnectionFlow}. This is useful to distinguish so that we know
     * whether or not we're in the process of establishing a connection.
     * 
     * @return true if part of connection flow, otherwise false
     *
     * 指示此ConnectionState是否对应于{@link ConnectionFlow}中的步骤。
     * 进行区分非常有用，这样可以使我们知道我们是否正在建立连接。
     * @return如果连接流的一部分为true，否则为false
     */
    public boolean isPartOfConnectionFlow() {
        return partOfConnectionFlow;
    }

    /**
     * Indicates whether this ConnectionState is no longer waiting for messages and is either in the process of disconnecting
     * or is already disconnected.
     *
     * 指示此ConnectionState是否不再等待消息并且正在断开连接或已经断开连接
     *
     * @return true if the connection state is {@link #DISCONNECT_REQUESTED} or {@link #DISCONNECTED}, otherwise false
     */
    public boolean isDisconnectingOrDisconnected() {
        return this == DISCONNECT_REQUESTED || this == DISCONNECTED;
    }
}
