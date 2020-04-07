package org.littleshoot.proxy.impl;

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.spi.LocationAwareLogger;

/**
 * <p>
 * A helper class that logs messages for ProxyConnections. All it does is make
 * sure that the Channel and current state are always included in the log
 * messages (if available).
 *
 * 记录ProxyConnections消息的帮助程序类。
 * 它所做的就是确保始终将通道和当前状态包含在日志消息中
 * </p>
 *
 * <p>
 * Note that this depends on us using a LocationAwareLogger so that we can
 * report the line numbers of the caller rather than this helper class.
 * If the SLF4J binding does not provide a LocationAwareLogger, then a fallback
 * to Logger is provided.
 *
 * 请注意，这取决于我们使用LocationAwareLogger，
 * 以便我们可以报告呼叫者的行号，而不是此帮助程序类。
 * 如果SLF4J绑定未提供LocationAwareLogger，则提供对Logger的后备
 * </p>
 */
class ProxyConnectionLogger {
    private final ProxyConnection connection;
    private final LogDispatch dispatch;
    private final Logger logger;
    private final String fqcn = this.getClass().getCanonicalName();

    public ProxyConnectionLogger(ProxyConnection connection) {
        this.connection = connection;
        final Logger lg = LoggerFactory.getLogger(connection
                .getClass());
        if (lg instanceof LocationAwareLogger) {
            dispatch = new LocationAwareLogggerDispatch((LocationAwareLogger) lg);
        }
        else {
            dispatch = new LoggerDispatch();
        }
        logger = lg;
    }

    protected void error(String message, Object... params) {
        if (logger.isErrorEnabled()) {
            dispatch.doLog(LocationAwareLogger.ERROR_INT, message, params, null);
        }
    }

    protected void error(String message, Throwable t) {
        if (logger.isErrorEnabled()) {
            dispatch.doLog(LocationAwareLogger.ERROR_INT, message, null, t);
        }
    }

    protected void warn(String message, Object... params) {
        if (logger.isWarnEnabled()) {
            dispatch.doLog(LocationAwareLogger.WARN_INT, message, params, null);
        }
    }

    protected void warn(String message, Throwable t) {
        if (logger.isWarnEnabled()) {
            dispatch.doLog(LocationAwareLogger.WARN_INT, message, null, t);
        }
    }

    protected void info(String message, Object... params) {
        if (logger.isInfoEnabled()) {
            dispatch.doLog(LocationAwareLogger.INFO_INT, message, params, null);
        }
    }

    protected void info(String message, Throwable t) {
        if (logger.isInfoEnabled()) {
            dispatch.doLog(LocationAwareLogger.INFO_INT, message, null, t);
        }
    }

    protected void debug(String message, Object... params) {
        if (logger.isDebugEnabled()) {
            dispatch.doLog(LocationAwareLogger.DEBUG_INT, message, params, null);
        }
    }

    protected void debug(String message, Throwable t) {
        if (logger.isDebugEnabled()) {
            dispatch.doLog(LocationAwareLogger.DEBUG_INT, message, null, t);
        }
    }

    protected void log(int level, String message, Object... params) {
        if (level != LocationAwareLogger.DEBUG_INT || logger.isDebugEnabled()) {
            dispatch.doLog(level, message, params, null);
        }
    }

    protected void log(int level, String message, Throwable t) {
        if (level != LocationAwareLogger.DEBUG_INT || logger.isDebugEnabled()) {
            dispatch.doLog(level, message, null, t);
        }
    }

    private interface LogDispatch {
        void doLog(int level, String message, Object[] params, Throwable t);
    }

    private String fullMessage(String message) {
        String stateMessage = connection.getCurrentState().toString();
        if (connection.isTunneling()) {
            stateMessage += " {tunneling}";
        }
        String messagePrefix = "(" + stateMessage + ")";
        if (connection.channel != null) {
            messagePrefix = messagePrefix + " " + connection.channel;
        }
        return messagePrefix + ": " + message;
    }

    /**
     * Fallback dispatch if a LocationAwareLogger is not available from
     * the SLF4J LoggerFactory.
     */
    private class LoggerDispatch implements LogDispatch {
        @Override
        public void doLog(int level, String message, Object[] params, Throwable t) {
            String formattedMessage = fullMessage(message);

            final Object[] paramsWithThrowable;

            if (t != null) {
                if (params == null) {
                    paramsWithThrowable = new Object[1];
                    paramsWithThrowable[0] = t;
                } else {
                    paramsWithThrowable = Arrays.copyOf(params, params.length + 1);
                    paramsWithThrowable[params.length] = t;
                }
            }
            else {
                paramsWithThrowable = params;
            }
            switch (level) {
            case LocationAwareLogger.TRACE_INT:
                logger.trace(formattedMessage, paramsWithThrowable);
                break;
            case LocationAwareLogger.DEBUG_INT:
                logger.debug(formattedMessage, paramsWithThrowable);
                break;
            case LocationAwareLogger.INFO_INT:
                logger.info(formattedMessage, paramsWithThrowable);
                break;
            case LocationAwareLogger.WARN_INT:
                logger.warn(formattedMessage, paramsWithThrowable);
                break;
            case LocationAwareLogger.ERROR_INT:
            default:
                logger.error(formattedMessage, paramsWithThrowable);
                break;
            }
        }
    }

    /**
     * Dispatcher for a LocationAwareLogger.
     */
    private class LocationAwareLogggerDispatch implements LogDispatch {

        private LocationAwareLogger log;

        public LocationAwareLogggerDispatch(LocationAwareLogger log) {
            this.log = log;
        }

        @Override
        public void doLog(int level, String message, Object[] params, Throwable t) {
            String formattedMessage = fullMessage(message);
            if (params != null && params.length > 0) {
                formattedMessage = MessageFormatter.arrayFormat(formattedMessage,
                        params).getMessage();
            }
            log.log(null, fqcn, level, formattedMessage, null, t);
        }
    }
}