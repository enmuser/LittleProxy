package org.littleshoot.proxy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

/**
 * Factory for {@link HttpFilters}.
 */
public interface HttpFiltersSource {
    /**
     * Return an {@link HttpFilters} object for this request if and only if we
     * want to filter the request and/or its responses.
     * 仅当我们要过滤请求和/或其响应时，才为此请求返回一个{@link HttpFilters}对象
     * 
     * @param originalRequest
     * @return
     */
    HttpFilters filterRequest(HttpRequest originalRequest,
            ChannelHandlerContext ctx);

    /**
     * Indicate how many (if any) bytes to buffer for incoming
     * {@link HttpRequest}s. A value of 0 or less indicates that no buffering
     * should happen and that messages will be passed to the {@link HttpFilters}
     * request filtering methods chunk by chunk. A positive value will cause
     * LittleProxy to try an create a {@link FullHttpRequest} using the data
     * received from the client, with its content already decompressed (in case
     * the client was compressing it). If the request size exceeds the maximum
     * buffer size, the request will fail.
     *
     * 指示要为传入的{@link HttpRequest}缓冲多少（如果有）字节。
     * 等于或小于0的值表示不应该进行缓冲，并且消息将逐块传递到{@link HttpFilters}请求过滤方法。
     * 正值将导致LittleProxy尝试使用从客户端接收的数据创建一个{@link FullHttpRequest}，
     * 并且其内容已经解压缩（如果客户端正在压缩它）。如果请求大小超过最大缓冲区大小，则请求将失败
     * 
     * @return
     */
    int getMaximumRequestBufferSizeInBytes();

  /**
   * Indicate how many (if any) bytes to buffer for incoming {@link HttpResponse}s. A value of 0 or
   * less indicates that no buffering should happen and that messages will be passed to the {@link
   * HttpFilters} response filtering methods chunk by chunk. A positive value will cause LittleProxy
   * to try an create a {@link FullHttpResponse} using the data received from the server, with its
   * content already decompressed (in case the server was compressing it). If the response size
   * exceeds the maximum buffer size, the response will fail.
   *
   * <p>指出要为传入的{@link HttpResponse}缓冲多少（如果有的话）字节。
   * 等于或小于0的值表示不应进行缓冲，并且消息将逐块传递到{@link
   * HttpFilters}响应过滤方法。 一个正值将导致LittleProxy尝试使用从服务器接收的数据创建一个{@link FullHttpResponse}，
   * 并且其内容已经解压缩（如果服务器正在压缩它）。 如果响应大小超过最大缓冲区大小，则响应将失败。
   *
   * @return
   */
  int getMaximumResponseBufferSizeInBytes();
}
