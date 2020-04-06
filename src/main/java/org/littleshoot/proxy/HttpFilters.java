package org.littleshoot.proxy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import org.littleshoot.proxy.impl.ProxyUtils;

import java.net.InetSocketAddress;

/**
 * Interface for objects that filter {@link HttpObject}s, including both requests and responses, and
 * informs of different steps in request/response.
 *
 * <p>Multiple methods are defined, corresponding to different steps in the request processing
 * lifecycle. Some of these methods is given the current object (request, response or chunk) and is
 * allowed to modify it in place. Others provide a notification of when specific operations happen
 * (i.e. connection in queue, DNS resolution, SSL handshaking and so forth).
 *
 * <p>Because HTTP transfers can be chunked, for any given request or response, the filter methods
 * that can modify request/response in place may be called multiple times, once for the initial
 * {@link HttpRequest} or {@link HttpResponse}, and once for each subsequent {@link HttpContent}.
 * The last chunk will always be a {@link LastHttpContent} and can be checked for being last using
 * {@link ProxyUtils#isLastChunk(HttpObject)}.
 *
 * <p>{@link HttpFiltersSource#getMaximumRequestBufferSizeInBytes()} and {@link
 * HttpFiltersSource#getMaximumResponseBufferSizeInBytes()} can be used to instruct the proxy to
 * buffer the {@link HttpObject}s sent to all of its request/response filters, in which case it will
 * buffer up to the specified limit and then send either complete {@link HttpRequest}s or {@link
 * HttpResponse}s to the filter methods. When buffering, if the proxy receives more data than fits
 * in the specified maximum bytes to buffer, the proxy will stop processing the request and respond
 * with a 502 Bad Gateway error.
 *
 * {@link HttpFiltersSource＃getMaximumRequestBufferSizeInBytes（）}和
 * 可以使用{@link HttpFiltersSource＃getMaximumResponseBufferSizeInBytes（）}  
 * 指示代理缓冲发送给所有代理的{@link HttpObject}  
 * 请求/响应过滤器，在这种情况下，它将缓冲到指定的数量限制，
 * 然后发送完整的{@link HttpRequest}或   {@link HttpResponse}到过滤器方法。
 * 缓冲时，如果代理接收到超出指定最大字节数以容纳的数据，
 * 代理将停止处理请求并以502错误网关响应错误。
 *
 * <p>A new instance of {@link HttpFilters} is created for each request, so these objects can be
 * stateful.
 *
 * <p>To monitor (and time measure?) the different steps the request/response goes through, many
 * informative methods are provided. Those steps are reported in the following order:
 *
 * <ol>
 *   <li>clientToProxyRequest
 *   <li>proxyToServerConnectionQueued
 *   <li>proxyToServerResolutionStarted
 *   <li>proxyToServerResolutionSucceeded
 *   <li>proxyToServerRequest (can be multiple if chunked)
 *   <li>proxyToServerConnectionStarted
 *   <li>proxyToServerConnectionFailed (if connection couldn't be established)
 *   <li>proxyToServerConnectionSSLHandshakeStarted (only if HTTPS required)
 *   <li>proxyToServerConnectionSucceeded
 *   <li>proxyToServerRequestSending
 *   <li>proxyToServerRequestSent
 *   <li>serverToProxyResponseReceiving
 *   <li>serverToProxyResponse (can be multiple if chuncked)
 *   <li>serverToProxyResponseReceived
 *   <li>proxyToClientResponse
 * </ol>
 */
public interface HttpFilters {
  /**
   * Filters requests on their way from the client to the proxy. To interrupt processing of this
   * request and return a response to the client immediately, return an HttpResponse here.
   * Otherwise, return null to continue processing as usual.
   * 过滤从客户端到代理的请求。
   * 要中断此请求的处理并立即将响应返回给客户端，请在此处返回HttpResponse。
   * 否则，返回null以照常继续处理。
   *
   * <p><b>Important:</b> When returning a response, you must include a mechanism to allow the
   * client to determine the length of the message (see RFC 7230, section 3.3.3:
   * https://tools.ietf.org/html/rfc7230#section-3.3.3 ). For messages that may contain a body, you
   * may do this by setting the Transfer-Encoding to chunked, setting an appropriate Content-Length,
   * or by adding a "Connection: close" header to the response (which will instruct LittleProxy to
   * close the connection). If the short-circuit response contains body content, it is recommended
   * that you return a FullHttpResponse.
   *
   * 返回响应时，必须包括一种机制，以允许客户端确定消息的长度（请参阅RFC7230，第3.3.3节：https://tools.ietf.org/html/rfc7230#section-3.3.3 ）。
   * 对于可能包含正文的消息，可以通过将“传输编码”设置为分块，设置适当的Content-Length或在响应中添加“
   * Connection:close”标头（这将指示LittleProxy关闭连接）来执行此操作 ）。
   * 如果短路响应包含主体内容，建议您返回一个 FullHttpResponse。
   *
   * @param httpObject Client to Proxy HttpRequest (and HttpContent, if chunked)
   * @return a short-circuit response, or null to continue processing as usual
   */
  HttpResponse clientToProxyRequest(HttpObject httpObject);

    /**
     * Filters requests on their way from the proxy to the server. To interrupt processing of this request and return a
     * response to the client immediately, return an HttpResponse here. Otherwise, return null to continue processing as
     * usual.
     * <p>
     * <b>Important:</b> When returning a response, you must include a mechanism to allow the client to determine the length
     * of the message (see RFC 7230, section 3.3.3: https://tools.ietf.org/html/rfc7230#section-3.3.3 ). For messages that
     * may contain a body, you may do this by setting the Transfer-Encoding to chunked, setting an appropriate
     * Content-Length, or by adding a "Connection: close" header to the response. (which will instruct LittleProxy to close
     * the connection). If the short-circuit response contains body content, it is recommended that you return a
     * FullHttpResponse.
     * 
     * @param httpObject Proxy to Server HttpRequest (and HttpContent, if chunked)
     * @return a short-circuit response, or null to continue processing as usual
     */
    HttpResponse proxyToServerRequest(HttpObject httpObject);

    /**
     * Informs filter that proxy to server request is being sent.
     * 通知过滤器正在发送对服务器请求的代理
     */
    void proxyToServerRequestSending();

    /**
     * Informs filter that the HTTP request, including any content, has been sent.
     * 通知筛选器已发送HTTP请求（包括任何内容）
     */
    void proxyToServerRequestSent();

    /**
     * Filters responses on their way from the server to the proxy.
     * 过滤从服务器到代理的响应。
     * 
     * @param httpObject
     *            Server to Proxy HttpResponse (and HttpContent, if chunked)
     * @return the modified (or unmodified) HttpObject. Returning null will
     *         force a disconnect.
     */
    HttpObject serverToProxyResponse(HttpObject httpObject);

    /**
     * Informs filter that a timeout occurred before the server response was received by the client. The timeout may have
     * occurred while the client was sending the request, waiting for a response, or after the client started receiving
     * a response (i.e. if the response from the server "stalls").
     *
     * 通知过滤器客户端接收到服务器响应之前发生了超时。
     * 客户端发送请求，等待响应时或客户端开始接收响应后（即，如果来自服务器的响应“停滞”），
     * 可能会发生超时。
     *
     * See {@link HttpProxyServerBootstrap#withIdleConnectionTimeout(int)} for information on setting the timeout.
     */
    void serverToProxyResponseTimedOut();

    /**
     * Informs filter that server to proxy response is being received.
     * 通知过滤器正在接收服务器到代理的响应。
     */
    void serverToProxyResponseReceiving();

    /**
     * Informs filter that server to proxy response has been received.
     *  通知筛选器已收到服务器到代理的响应
     */
    void serverToProxyResponseReceived();

  /**
   * Filters responses on their way from the proxy to the client.
   * 过滤从代理到客户端的响应。
   *
   * @param httpObject Proxy to Client HttpResponse (and HttpContent, if chunked)
   * @return the modified (or unmodified) HttpObject. Returning null will force a disconnect.
   */
  HttpObject proxyToClientResponse(HttpObject httpObject);

    /**
     * Informs filter that proxy to server connection is in queue.
     * 通知过滤器队列中有与服务器连接的代理
     */
    void proxyToServerConnectionQueued();

    /**
     * Filter DNS resolution from proxy to server.
     * 从代理到服务器过滤DNS解析
     * 
     * @param resolvingServerHostAndPort
     *            Server "HOST:PORT"
     * @return alternative address resolution. Returning null will let normal
     *         DNS resolution continue.
     */
    InetSocketAddress proxyToServerResolutionStarted(
            String resolvingServerHostAndPort);

    /**
     * Informs filter that proxy to server DNS resolution failed for the specified host and port.
     * 通知筛选器，指定主机和端口的服务器DNS解析代理失败。
     *
     * @param hostAndPort hostname and port the proxy failed to resolve
     */
    void proxyToServerResolutionFailed(String hostAndPort);

    /**
     * Informs filter that proxy to server DNS resolution has happened.
     * 通知过滤器已发生代理服务器DNS解析的事件
     * 
     * @param serverHostAndPort
     *            Server "HOST:PORT"
     * @param resolvedRemoteAddress
     *            Address it was proxyToServerResolutionSucceeded to
     */
    void proxyToServerResolutionSucceeded(String serverHostAndPort,
            InetSocketAddress resolvedRemoteAddress);

    /**
     * Informs filter that proxy to server connection is initiating.
     * 通知过滤器正在启动与服务器连接的代理
     */
    void proxyToServerConnectionStarted();

    /**
     * Informs filter that proxy to server ssl handshake is initiating.
     * 通知过滤器正在启动对服务器ssl握手的代理。
     */
    void proxyToServerConnectionSSLHandshakeStarted();

    /**
     * Informs filter that proxy to server connection has failed.
     * 通知过滤器代理服务器连接失败
     */
    void proxyToServerConnectionFailed();

    /**
     * Informs filter that proxy to server connection has succeeded.
     * 通知过滤器代理服务器连接成功
     *
     * @param serverCtx the {@link io.netty.channel.ChannelHandlerContext} used to connect to the server
     */
    void proxyToServerConnectionSucceeded(ChannelHandlerContext serverCtx);

}
