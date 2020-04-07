package org.littleshoot.proxy.impl;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.udt.nio.NioUdtProvider;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * Utilities for the proxy.
 */
public class ProxyUtils {
    /**
     * Hop-by-hop headers that should be removed when proxying, as defined by the HTTP 1.1 spec, section 13.5.1
     * 代理时应删除的逐跳标头，如HTTP 1.1规范第13.5.1节所定义
     * (http://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html#sec13.5.1).
     * Transfer-Encoding is NOT included in this list, since LittleProxy
     * does not typically modify the transfer encoding. See also {@link #shouldRemoveHopByHopHeader(String)}.
     * 传输编码未包含在此列表中，因为LittleProxy 通常不会修改传输编码。
     * 另请参见{@link #shouldRemoveHopByHopHeader（String）}
     *
     * Header names are stored as lowercase to make case-insensitive comparisons easier.
     * 标头名称存储为小写，以使不区分大小写的比较更容易
     */
    private static final Set<String> SHOULD_NOT_PROXY_HOP_BY_HOP_HEADERS = ImmutableSet.of(
            HttpHeaders.Names.CONNECTION.toLowerCase(Locale.US),
            HttpHeaders.Names.PROXY_AUTHENTICATE.toLowerCase(Locale.US),
            HttpHeaders.Names.PROXY_AUTHORIZATION.toLowerCase(Locale.US),
            HttpHeaders.Names.TE.toLowerCase(Locale.US),
            HttpHeaders.Names.TRAILER.toLowerCase(Locale.US),
            /*  Note: Not removing Transfer-Encoding since LittleProxy does not normally re-chunk content.
                HttpHeaders.Names.TRANSFER_ENCODING.toLowerCase(Locale.US), */
            /*
              注意：由于LittleProxy通常不会重新打包内容，因此请不要删除Transfer-Encoding。
              HttpHeaders.Names.TRANSFER_ENCODING.toLowerCase（Locale.US）
             */
            HttpHeaders.Names.UPGRADE.toLowerCase(Locale.US),
            "Keep-Alive".toLowerCase(Locale.US)
    );

    private static final Logger LOG = LoggerFactory.getLogger(ProxyUtils.class);

    private static final TimeZone GMT = TimeZone.getTimeZone("GMT");

    /**Splits comma-separated header values (such as Connection) into their individual tokens.
     * 将逗号分隔的标头值（例如Connection）拆分为各自的标记
     *
     */
    private static final Splitter COMMA_SEPARATED_HEADER_VALUE_SPLITTER = Splitter.on(',').trimResults().omitEmptyStrings();

    /**
     * Date format pattern used to parse HTTP date headers in RFC 1123 format.
     */
    private static final String PATTERN_RFC1123 = "EEE, dd MMM yyyy HH:mm:ss zzz";

    // Schemes are case-insensitive:
    // http://tools.ietf.org/html/rfc3986#section-3.1
    private static Pattern HTTP_PREFIX = Pattern.compile("^https?://.*",
            Pattern.CASE_INSENSITIVE);

    /**
     * Strips the host from a URI string. This will turn "http://host.com/path"
     * into "/path".
     * 从URI字符串中剥离主机。这会将"http://host.com/path" 转换为"/path"。
     * 
     * @param uri
     *            The URI to transform.
     * @return A string with the URI stripped.
     */
    public static String stripHost(final String uri) {
        if (!HTTP_PREFIX.matcher(uri).matches()) {
            // It's likely a URI path, not the full URI (i.e. the host is
            // already stripped).
            // 这可能是URI路径，而不是完整的URI（即主机已经被剥离）
            return uri;
        }
        final String noHttpUri = StringUtils.substringAfter(uri, "://");
        final int slashIndex = noHttpUri.indexOf("/");
        if (slashIndex == -1) {
            return "/";
        }
        final String noHostUri = noHttpUri.substring(slashIndex);
        return noHostUri;
    }

    /**
     * Formats the given date according to the RFC 1123 pattern.
     * 
     * @param date
     *            The date to format.
     * @return An RFC 1123 formatted date string.
     * 
     * @see #PATTERN_RFC1123
     */
    public static String formatDate(final Date date) {
        return formatDate(date, PATTERN_RFC1123);
    }

    /**
     * Formats the given date according to the specified pattern. The pattern
     * must conform to that used by the {@link SimpleDateFormat simple date
     * format} class.
     * 
     * @param date
     *            The date to format.
     * @param pattern
     *            The pattern to use for formatting the date.
     * @return A formatted date string.
     * 
     * @throws IllegalArgumentException
     *             If the given date pattern is invalid.
     * 
     * @see SimpleDateFormat
     */
    public static String formatDate(final Date date, final String pattern) {
        if (date == null)
            throw new IllegalArgumentException("date is null");
        if (pattern == null)
            throw new IllegalArgumentException("pattern is null");

        final SimpleDateFormat formatter = new SimpleDateFormat(pattern,
                Locale.US);
        formatter.setTimeZone(GMT);
        return formatter.format(date);
    }

    /**
     * If an HttpObject implements the market interface LastHttpContent, it
     * represents the last chunk of a transfer.
     * 
     * @see io.netty.handler.codec.http.LastHttpContent
     * 
     * @param httpObject
     * @return
     * 
     */
    public static boolean isLastChunk(final HttpObject httpObject) {
        return httpObject instanceof LastHttpContent;
    }

    /**
     * If an HttpObject is not the last chunk, then that means there are other
     * chunks that will follow.
     * 
     * @see io.netty.handler.codec.http.FullHttpMessage
     * 
     * @param httpObject
     * @return
     */
    public static boolean isChunked(final HttpObject httpObject) {
        return !isLastChunk(httpObject);
    }

    /**
     * Parses the host and port an HTTP request is being sent to.
     * 解析向其发送HTTP请求的主机和端口。
     * 
     * @param httpRequest
     *            The request.
     * @return The host and port string.
     */
    public static String parseHostAndPort(final HttpRequest httpRequest) {
        final String uriHostAndPort = parseHostAndPort(httpRequest.getUri());
        return uriHostAndPort;
    }

    /**
     * Parses the host and port an HTTP request is being sent to.
     * 解析向其发送HTTP请求的主机和端口。
     * 
     * @param uri
     *            The URI.
     * @return The host and port string.
     */
    public static String parseHostAndPort(final String uri) {
        final String tempUri;
        if (!HTTP_PREFIX.matcher(uri).matches()) {
            // Browsers particularly seem to send requests in this form when
            // they use CONNECT.
            // 浏览器在使用CONNECT时似乎特别以这种形式发送请求
            tempUri = uri;
        } else {
            // We can't just take a substring from a hard-coded index because it
            // could be either http or https.
            // 我们不能只从硬编码索引中获取子字符串，因为它可以是http或https
            tempUri = StringUtils.substringAfter(uri, "://");
        }
        final String hostAndPort;
        if (tempUri.contains("/")) {
            hostAndPort = tempUri.substring(0, tempUri.indexOf("/"));
        } else {
            hostAndPort = tempUri;
        }
        return hostAndPort;
    }

    /**
     * Make a copy of the response including all mutable fields.
     * 复制响应，包括所有可变字段。
     * 
     * @param original
     *            The original response to copy from.
     *            复制的原始回复
     * @return The copy with all mutable fields from the original.
     *          具有原始所有可变字段的副本
     */
    public static HttpResponse copyMutableResponseFields(
            final HttpResponse original) {

        HttpResponse copy = null;
        if (original instanceof DefaultFullHttpResponse) {
            ByteBuf content = ((DefaultFullHttpResponse) original).content();
            copy = new DefaultFullHttpResponse(original.getProtocolVersion(),
                    original.getStatus(), content);
        } else {
            copy = new DefaultHttpResponse(original.getProtocolVersion(),
                    original.getStatus());
        }
        final Collection<String> headerNames = original.headers().names();
        for (final String name : headerNames) {
            final List<String> values = original.headers().getAll(name);
            copy.headers().set(name, values);
        }
        return copy;
    }

  /**
   * Via 是一个通用首部，是由代理服务器添加的，适用于正向和反向代理，在请求和响应首部中均可出现。
   * 这个消息首部可以用来追踪消息转发情况，防止循环请求，以及识别在请求或响应传递链中消息发送者对于协议的支持能力。
   *
   * <p>“ Via”标头字段指示存在中间体 用户代理和服务器之间的协议和收件人（在 请求）或在原始服务器和客户端之间（根据响应），
   * 类似于电子邮件中的“已接收”标头字段（第3.6.7节
   * [RFC5322]）。Via可用于跟踪消息转发，避免 请求循环，并确定发送方的协议功能 沿着请求/响应链。
   *
   * Adds the Via header to specify that the
   * message has passed through the proxy. The specified alias will be appended to the Via header
   * line. The alias may be the hostname of the machine proxying the request, or a pseudonym. From
   * RFC 7230, section 5.7.1: *
   *
   * <p>添加Via标头以指定消息已通过代理传递。指定的别名将附加到Via标题行。 别名可以是代理请求的计算机的主机名，也可以是化名。根据RFC 7230第5.7.1节：
   *
   * <pre>
   *          The received-by portion of the field value is normally the host and
   *          optional port number of a recipient server or client that
   *          subsequently forwarded the message.  However, if the real host is
   *          considered to be sensitive information, a sender MAY replace it with
   *          a pseudonym.
   *          字段值的“接收人”部分通常是随后转发消息的接收方服务器或客户端的主机和可选端口号。
   *          但是，如果真实主机被认为是敏感信息，则发送者可以用化名代替它。
   * </pre>
   *
   * @param httpMessage HTTP message to add the Via header to httpMessage HTTP消息，用于将Via头添加到
   * @param alias the alias to provide in the Via header for this proxy 别名以在此代理的Via标头中提供
   */
  public static void addVia(HttpMessage httpMessage, String alias) {
        String newViaHeader =  new StringBuilder()
                .append(httpMessage.getProtocolVersion().majorVersion())
                .append('.')
                .append(httpMessage.getProtocolVersion().minorVersion())
                .append(' ')
                .append(alias)
                .toString();

        final List<String> vias;
        if (httpMessage.headers().contains(HttpHeaders.Names.VIA)) {
            List<String> existingViaHeaders = httpMessage.headers().getAll(HttpHeaders.Names.VIA);
            vias = new ArrayList<String>(existingViaHeaders);
            vias.add(newViaHeader);
        } else {
            vias = Collections.singletonList(newViaHeader);
        }

        httpMessage.headers().set(HttpHeaders.Names.VIA, vias);
    }

    /**
     * Returns <code>true</code> if the specified string is either "true" or
     * "on" ignoring case.
     * 
     * @param val
     *            The string in question.
     * @return <code>true</code> if the specified string is either "true" or
     *         "on" ignoring case, otherwise <code>false</code>.
     */
    public static boolean isTrue(final String val) {
        return checkTrueOrFalse(val, "true", "on");
    }

    /**
     * Returns <code>true</code> if the specified string is either "false" or
     * "off" ignoring case.
     * 
     * @param val
     *            The string in question.
     * @return <code>true</code> if the specified string is either "false" or
     *         "off" ignoring case, otherwise <code>false</code>.
     */
    public static boolean isFalse(final String val) {
        return checkTrueOrFalse(val, "false", "off");
    }

    public static boolean extractBooleanDefaultFalse(final Properties props,
            final String key) {
        final String throttle = props.getProperty(key);
        if (StringUtils.isNotBlank(throttle)) {
            return throttle.trim().equalsIgnoreCase("true");
        }
        return false;
    }

    public static boolean extractBooleanDefaultTrue(final Properties props,
            final String key) {
        final String throttle = props.getProperty(key);
        if (StringUtils.isNotBlank(throttle)) {
            return throttle.trim().equalsIgnoreCase("true");
        }
        return true;
    }
    
    public static int extractInt(final Properties props, final String key) {
        return extractInt(props, key, -1);
    }
    
    public static int extractInt(final Properties props, final String key, int defaultValue) {
        final String readThrottleString = props.getProperty(key);
        if (StringUtils.isNotBlank(readThrottleString) &&
            NumberUtils.isNumber(readThrottleString)) {
            return Integer.parseInt(readThrottleString);
        }
        return defaultValue;
    }

    public static boolean isCONNECT(HttpObject httpObject) {
        return httpObject instanceof HttpRequest
                && HttpMethod.CONNECT.equals(((HttpRequest) httpObject)
                        .getMethod());
    }

    /**
     * Returns true if the specified HttpRequest is a HEAD request.
     * 如果指定的HttpRequest是HEAD请求，则返回true
     *
     * @param httpRequest http request
     * @return true if request is a HEAD, otherwise false
     */
    public static boolean isHEAD(HttpRequest httpRequest) {
        return HttpMethod.HEAD.equals(httpRequest.getMethod());
    }

    private static boolean checkTrueOrFalse(final String val,
            final String str1, final String str2) {
        final String str = val.trim();
        return StringUtils.isNotBlank(str)
                && (str.equalsIgnoreCase(str1) || str.equalsIgnoreCase(str2));
    }

    /**
     * Returns true if the HTTP message cannot contain an entity body, according to the HTTP spec. This code is taken directly
     * from {@link io.netty.handler.codec.http.HttpObjectDecoder#isContentAlwaysEmpty(HttpMessage)}.
     *
     * 根据HTTP规范，如果HTTP消息不能包含实体主体，则返回true。
     * 此代码直接从{@link io.netty.handler.codec.http.HttpObjectDecoder＃isContentAlwaysEmpty（HttpMessage）}中获取。
     *
     * @param msg HTTP message
     * @return true if the HTTP message is always empty, false if the message <i>may</i> have entity content.
     *
     *  如果HTTP消息始终为空，则返回rue；如果消息<i>可能</ i>具有实体内容，则返回false
     */
    public static boolean isContentAlwaysEmpty(HttpMessage msg) {
        if (msg instanceof HttpResponse) {
            HttpResponse res = (HttpResponse) msg;
            int code = res.getStatus().code();

            // Correctly handle return codes of 1xx.
            //
            // See:
            //     - http://www.w3.org/Protocols/rfc2616/rfc2616-sec4.html Section 4.4
            //     - https://github.com/netty/netty/issues/222
            if (code >= 100 && code < 200) {
        // According to RFC 7231, section 6.1, 1xx responses have no content
        // (https://tools.ietf.org/html/rfc7231#section-6.2):
        // 根据RFC 7231第6.1节，1xx响应不包含任何内容（https://tools.ietf.org/html/rfc7231#section-6.2）：
        //   1xx responses are terminated by the first empty line after
        // 1xx响应由后面的第一个空行终止
        //   the status-line (the empty line signaling the end of the header
        //        section).
        // 状态行（表示标头结尾的空行部分）

        // Hixie 76 websocket handshake responses contain a 16-byte body, so their content is not
        // empty; but Hixie 76
        // was a draft specification that was superceded by RFC 6455. Since it is rarely used and
        // doesn't conform to
        // RFC 7231, we do not support or make special allowance for Hixie 76 responses.
         /*
           Hixie 76 Websocket握手响应包含一个16字节的正文，因此其内容不为空。
           但是Hixie 76是由RFC 6455取代的规范草案。由于它很少使用且不符合RFC 7231，
           因此我们不支持Hixie 76响应或对此没有特殊的要求。
          */
        return true;
            }

            switch (code) {
                case 204: case 205: case 304:
                    return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the HTTP response from the server is expected to indicate its own message length/end-of-message. Returns false
     * if the server is expected to indicate the end of the HTTP entity by closing the connection.
     *
     * 如果期望服务器的HTTP响应指示其自己的消息长度/消息结束，则返回true。
     * 如果期望服务器通过关闭连接来指示HTTP实体的结尾，则返回false。
     * <p>
     * This method is based on the allowed message length indicators in the HTTP specification, section 4.4:
     * 此方法基于HTTP规范第4.4节中允许的消息长度指示符
     * <pre>
         4.4 Message Length
         The transfer-length of a message is the length of the message-body as it appears in the message; that is, after any transfer-codings have been applied. When a message-body is included with a message, the transfer-length of that body is determined by one of the following (in order of precedence):

         1.Any response message which "MUST NOT" include a message-body (such as the 1xx, 204, and 304 responses and any response to a HEAD request) is always terminated by the first empty line after the header fields, regardless of the entity-header fields present in the message.
         2.If a Transfer-Encoding header field (section 14.41) is present and has any value other than "identity", then the transfer-length is defined by use of the "chunked" transfer-coding (section 3.6), unless the message is terminated by closing the connection.
         3.If a Content-Length header field (section 14.13) is present, its decimal value in OCTETs represents both the entity-length and the transfer-length. The Content-Length header field MUST NOT be sent if these two lengths are different (i.e., if a Transfer-Encoding
         header field is present). If a message is received with both a Transfer-Encoding header field and a Content-Length header field, the latter MUST be ignored.
         [LP note: multipart/byteranges support has been removed from the HTTP 1.1 spec by RFC 7230, section A.2. Since it is seldom used, LittleProxy does not check for it.]
         5.By the server closing the connection. (Closing the connection cannot be used to indicate the end of a request body, since that would leave no possibility for the server to send back a response.)

         4.4消息长度
              消息的传输长度是出现在消息中的消息正文的长度。也就是说，在应用了任何传输编码之后。当消息主体包含在消息中时，该主体的传输长度由以下其中一项（按优先顺序）确定：
              1.任何“不得”包含消息正文的响应消息（例如1xx，204和304响应以及对HEAD请求的任何响应）总是由标头字段之后的第一个空行终止，无论消息中存在实体标题字段。
              2.如果存在一个传输编码报头字段（第14.41节），并且具有除“ identity”以外的任何值，则除非使用此消息，否则将使用“块式”传输编码（第3.6节）来定义传输长度。通过关闭连接终止。
              3.如果存在Content-Length头字段（第14.13节），则其在OCTET中的十进制值表示实体长度和传输长度。如果这两个长度不同（即，如果传输编码，则不能发送Content-Length标头字段）
              标头字段存在）。如果收到的消息同时具有传输编码头域和内容长度头域，则后者必须被忽略。
              [LP注意：RFC 7230的A.2部分已从HTTP 1.1规范中删除了对多部分/字节范围的支持。由于很少使用，因此LittleProxy不会对其进行检查。]
              5.通过服务器关闭连接。 （关闭连接不能用于指示请求主体的结尾，因为这将使服务器无法发送回响应。）
     * </pre>
     *
     * The rules for Transfer-Encoding are clarified in RFC 7230, section 3.3.1 and 3.3.3 (3):
     * <pre>
         If any transfer coding other than
         chunked is applied to a response payload body, the sender MUST either
         apply chunked as the final transfer coding or terminate the message
         by closing the connection.
         如果将除分块之外的任何传输编码应用于响应有效载荷主体，
         则发送方务必将分块作为最终传输编码应用，或通过关闭连接来终止消息
     * </pre>
     *
     *
     * @param response the HTTP response object
     * @return true if the message will indicate its own message length, or false if the server is expected to indicate the message length by closing the connection
     *
     *  如果消息将指示其自己的消息长度，则为true；
     *  如果期望服务器通过关闭连接来指示消息长度，则为false
     */
    public static boolean isResponseSelfTerminating(HttpResponse response) {
        if (isContentAlwaysEmpty(response)) {
            return true;
        }

        // if there is a Transfer-Encoding value, determine whether the final encoding is "chunked", which makes the message self-terminating
         // 如果有一个Transfer-Encoding值，请确定最终编码是否为“块式”，从而使消息自终止
        List<String> allTransferEncodingHeaders = getAllCommaSeparatedHeaderValues(HttpHeaders.Names.TRANSFER_ENCODING, response);
        if (!allTransferEncodingHeaders.isEmpty()) {
            String finalEncoding = allTransferEncodingHeaders.get(allTransferEncodingHeaders.size() - 1);

            // per #3 above: "If a message is received with both a Transfer-Encoding header field and a Content-Length header field, the latter MUST be ignored."
            // since the Transfer-Encoding field is present, the message is self-terminating if and only if the final Transfer-Encoding value is "chunked"
            /*
              按照上面的＃3：“如果同时收到带有Transfer-Encoding头字段和Content-Length头字段的消息，则必须忽略后者。”
              由于存在Transfer-Encoding字段，因此当且仅当最终的Transfer-Encoding值是“ chunked”时，消息才自行终止
             */
            return HttpHeaders.Values.CHUNKED.equals(finalEncoding);
        }

        String contentLengthHeader = HttpHeaders.getHeader(response, HttpHeaders.Names.CONTENT_LENGTH);
        if (contentLengthHeader != null && !contentLengthHeader.isEmpty()) {
            return true;
        }

        // not checking for multipart/byteranges, since it is seldom used and its use as a message length indicator was removed in RFC 7230

        // 不检查multipart / byteranges，因为它很少使用，并且在RFC 7230中已将其用作消息长度指示符
        // none of the other message length indicators are present, so the only way the server can indicate the end
        // of this message is to close the connection
        // 没有其他消息长度指示符，因此服务器可以指示此消息结束的唯一方法是关闭连接
        return false;
    }

    /**
     * Retrieves all comma-separated values for headers with the specified name on the HttpMessage. Any whitespace (spaces
     * or tabs) surrounding the values will be removed. Empty values (e.g. two consecutive commas, or a value followed
     * by a comma and no other value) will be removed; they will not appear as empty elements in the returned list.
     * If the message contains repeated headers, their values will be added to the returned list in the order in which
     * the headers appear. For example, if a message has headers like:
     *
     * 检索HttpMessage上具有指定名称的标头的所有逗号分隔值。
     * 值周围的所有空格（空格或制表符）将被删除。
     * 空值（例如两个连续的逗号，或者后面跟着逗号但没有其他值的值）将被删除； 它们不会在返回的列表中显示为空元素。
     * 如果消息包含重复的标题，则它们的值将按照标题出现的顺序添加到返回列表中。 例如，如果邮件的标题如下：
     * <pre>
     *     Transfer-Encoding: gzip,deflate
     *     Transfer-Encoding: chunked
     * </pre>
     * This method will return a list of three values: "gzip", "deflate", "chunked".
     * 此方法将返回三个值的列表：“ gzip”，“ deflate”，“ chunked”
     * <p>
     * Placing values on multiple header lines is allowed under certain circumstances
     * in RFC 2616 section 4.2, and in RFC 7230 section 3.2.2 quoted here:
     * 在某些情况下，允许在多个标头行上放置值在RFC 2616第4.2节和RFC 7230第3.2.2节中引用在此处
     * <pre>
     A sender MUST NOT generate multiple header fields with the same field
     name in a message unless either the entire field value for that
     header field is defined as a comma-separated list [i.e., #(values)]
     or the header field is a well-known exception (as noted below).

     A recipient MAY combine multiple header fields with the same field
     name into one "field-name: field-value" pair, without changing the
     semantics of the message, by appending each subsequent field value to
     the combined field value in order, separated by a comma.  The order
     in which header fields with the same field name are received is
     therefore significant to the interpretation of the combined field
     value; a proxy MUST NOT change the order of these field values when
     forwarding a message.
     发件人不得在消息中生成多个具有相同字段名称的标题字段，
     除非该标题字段的整个字段值都定义为逗号分隔的列表[即，＃（values）]或标题字段为-已知异常（如下所述）。
     接收者可以通过将每个随后的字段值按顺序附加到合并的字段值上，并用一个分隔符将多个具有相同字段名的头字段组合成一对“字段名：字段值”，
     而不会改变消息的语义。逗号。因此，接收具有相同字段名称的头字段的顺序对于组合字段值的解释很重要；代理在转发消息时不得更改这些字段值的顺序
     * </pre>
     * @param headerName the name of the header for which values will be retrieved
     *                   headerName标头的名称，将为其检索值
     * @param httpMessage the HTTP message whose header values will be retrieved
     *                     httpMessage HTTP消息，其标题值将被检索
     * @return a list of single header values, or an empty list if the header was not present in the message or contained no values
     *         单个标头值的列表，如果邮件中不存在标头或不包含任何值，则为空列表
     */
    public static List<String> getAllCommaSeparatedHeaderValues(String headerName, HttpMessage httpMessage) {
        List<String> allHeaders = httpMessage.headers().getAll(headerName);
        if (allHeaders.isEmpty()) {
            return Collections.emptyList();
        }

        ImmutableList.Builder<String> headerValues = ImmutableList.builder();
        for (String header : allHeaders) {
            List<String> commaSeparatedValues = splitCommaSeparatedHeaderValues(header);
            headerValues.addAll(commaSeparatedValues);
        }

        return headerValues.build();
    }

    /**
     * Duplicates the status line and headers of an HttpResponse object. Does not duplicate any content associated with that response.
     *
     * @param originalResponse HttpResponse to be duplicated
     * @return a new HttpResponse with the same status line and headers
     */
    public static HttpResponse duplicateHttpResponse(HttpResponse originalResponse) {
        DefaultHttpResponse newResponse = new DefaultHttpResponse(originalResponse.getProtocolVersion(), originalResponse.getStatus());
        newResponse.headers().add(originalResponse.headers());

        return newResponse;
    }

    /**
     * Attempts to resolve the local machine's hostname.
     *
     * @return the local machine's hostname, or null if a hostname cannot be determined
     */
    public static String getHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            LOG.debug("Ignored exception", e);
        } catch (RuntimeException e) {
            // An exception here must not stop the proxy. Android could throw a
            // runtime exception, since it not allows network access in the main
            // process.
            LOG.debug("Ignored exception", e);
        }
        LOG.info("Could not lookup localhost");
        return null;
    }

  /**
   * Determines if the specified header should be removed from the proxied response because it is a
   * hop-by-hop header, as defined by the HTTP 1.1 spec in section 13.5.1. The comparison is
   * case-insensitive, so "Connection" will be treated the same as "connection" or "CONNECTION".
   * 确定是否应将指定的标头从代理响应中删除，
   * 因为它是按需定义的逐跳标头 第13.5.1节中的HTTP 1.1规范。
   * 比较是不区分大小写的，因此“连接”将被视为与“连接”或“连接”相同。 From
   * http://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html#sec13.5.1 :
   *
   * <pre>
   * The following HTTP/1.1 headers are hop-by-hop headers:
   * - Connection
   * - Keep-Alive
   * - Proxy-Authenticate
   * - Proxy-Authorization
   * - TE
   * - Trailers [LittleProxy note: actual header name is Trailer][LittleProxy注意：实际的标头名称为Trailer]
   * - Transfer-Encoding [LittleProxy note: this header is not normally removed when proxying, since the proxy does not re-chunk
   * responses. The exception is when an HttpObjectAggregator is enabled, which aggregates chunked content and removes
   * the 'Transfer-Encoding: chunked' header itself.]
   * [LittleProxy注意：代理时通常不会删除此标头，因为代理不会重新打包响应。
   * 例外是启用HttpObjectAggregator时，它将聚集分块的内容并删除“ Transfer-Encoding：chunked”标头本身]
   * - Upgrade
   *
   * All other headers defined by HTTP/1.1 are end-to-end headers.
   * </pre>
   *
   * @param headerName the header name
   * @return true if this header is a hop-by-hop header and should be removed when proxying,
   *     //如果此标头是逐跳标头，并且在代理时应将其删除
   *     otherwise false
   */
  public static boolean shouldRemoveHopByHopHeader(String headerName) {
        return SHOULD_NOT_PROXY_HOP_BY_HOP_HEADERS.contains(headerName.toLowerCase(Locale.US));
    }

    /**
     * Splits comma-separated header values into tokens. For example, if the value of the Connection header is "Transfer-Encoding, close",
     * this method will return "Transfer-Encoding" and "close". This method strips trims any optional whitespace from
     * the tokens. Unlike {@link #getAllCommaSeparatedHeaderValues(String, HttpMessage)}, this method only operates on
     * a single header value, rather than all instances of the header in a message.
     *
     * 将以逗号分隔的标头值拆分为令牌。
     * 例如，如果Connection标头的值为“ Transfer-Encoding，close”，则此方法将返回“ Transfer-Encoding”和“ close”。
     * 此方法从标记中去除所有可选的空白。
     * 与{@link #getAllCommaSeparatedHeaderValues（String，HttpMessage）}不同，
     * 此方法仅对单个标头值进行操作，而不对消息中标头的所有实例进行操作。
     *
     * @param headerValue the un-tokenized header value (must not be null)
     * @return all tokens within the header value, or an empty list if there are no values
     */
    public static List<String> splitCommaSeparatedHeaderValues(String headerValue) {
        return ImmutableList.copyOf(COMMA_SEPARATED_HEADER_VALUE_SPLITTER.split(headerValue));
    }

    /**
     * Determines if UDT is available on the classpath.
     *
     * @return true if UDT is available
     */
    public static boolean isUdtAvailable() {
        try {
            return NioUdtProvider.BYTE_PROVIDER != null;
        } catch (NoClassDefFoundError e) {
            return false;
        }
    }

    /**
     * Creates a new {@link FullHttpResponse} with the specified String as the body contents (encoded using UTF-8).
     *
     * @param httpVersion HTTP version of the response
     * @param status HTTP status code
     * @param body body to include in the FullHttpResponse; will be UTF-8 encoded
     * @return new http response object
     */
    public static FullHttpResponse createFullHttpResponse(HttpVersion httpVersion,
                                                          HttpResponseStatus status,
                                                          String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ByteBuf content = Unpooled.copiedBuffer(bytes);

        return createFullHttpResponse(httpVersion, status, "text/html; charset=utf-8", content, bytes.length);
    }

    /**
     * Creates a new {@link FullHttpResponse} with no body content
     *
     * @param httpVersion HTTP version of the response
     * @param status HTTP status code
     * @return new http response object
     */
    public static FullHttpResponse createFullHttpResponse(HttpVersion httpVersion,
                                                          HttpResponseStatus status) {
        return createFullHttpResponse(httpVersion, status, null, null, 0);
    }

    /**
     * Creates a new {@link FullHttpResponse} with the specified body.
     *
     * @param httpVersion HTTP version of the response
     * @param status HTTP status code
     * @param contentType the Content-Type of the body
     * @param body body to include in the FullHttpResponse; if null
     * @param contentLength number of bytes to send in the Content-Length header; should equal the number of bytes in the ByteBuf
     * @return new http response object
     */
    public static FullHttpResponse createFullHttpResponse(HttpVersion httpVersion,
                                                          HttpResponseStatus status,
                                                          String contentType,
                                                          ByteBuf body,
                                                          int contentLength) {
        DefaultFullHttpResponse response;

        if (body != null) {
            response = new DefaultFullHttpResponse(httpVersion, status, body);
            response.headers().set(HttpHeaders.Names.CONTENT_LENGTH, contentLength);
            response.headers().set(HttpHeaders.Names.CONTENT_TYPE, contentType);
        } else {
            response = new DefaultFullHttpResponse(httpVersion, status);
        }

        return response;
    }

    /**
     * Given an HttpHeaders instance, removes 'sdch' from the 'Accept-Encoding'
     * header list (if it exists) and returns the modified instance.
     * 给定一个HttpHeaders实例，从'Accept-Encoding' 头列表（如果存在）中删除'sdch'并返回修改后的实例
     *
     * sdch是Shared Dictionary Compression over HTTP的缩写，即通过字典压缩算法对各个页面中相同的内容进行压缩，减少相同的内容的传输
     *
     * Removes all occurrences of 'sdch' from the 'Accept-Encoding' header.
     * @param headers The headers to modify.
     */
    public static void removeSdchEncoding(HttpHeaders headers) {
        List<String> encodings = headers.getAll(HttpHeaders.Names.ACCEPT_ENCODING);
        headers.remove(HttpHeaders.Names.ACCEPT_ENCODING);

        for (String encoding : encodings) {
            if (encoding != null) {
                // The former regex should remove occurrences of 'sdch' while the
                // latter regex should take care of the dangling comma case when
                // 'sdch' was the first element in the list and there are other
                // encodings.
                /*
                 * 前一个正则表达式应删除出现的'sdch'，而后一个正则表达式应处理悬挂的逗号情况，
                 * 'sdch' 是列表中的第一个元素，并且还有其他编码
                 */
                encoding = encoding.replaceAll(",? *(sdch|SDCH)", "").replaceFirst("^ *, *", "");

                if (StringUtils.isNotBlank(encoding)) {
                    headers.add(HttpHeaders.Names.ACCEPT_ENCODING, encoding);
                }
            }
        }
    }
}
