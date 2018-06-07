/*
 *  Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.wso2.transport.http.netty.contractimpl.websocket;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.transport.http.netty.common.Constants;
import org.wso2.transport.http.netty.contract.websocket.WebSocketBinaryMessage;
import org.wso2.transport.http.netty.contract.websocket.WebSocketCloseMessage;
import org.wso2.transport.http.netty.contract.websocket.WebSocketConnectorException;
import org.wso2.transport.http.netty.contract.websocket.WebSocketConnectorFuture;
import org.wso2.transport.http.netty.contract.websocket.WebSocketControlMessage;
import org.wso2.transport.http.netty.contract.websocket.WebSocketControlSignal;
import org.wso2.transport.http.netty.contract.websocket.WebSocketFrameType;
import org.wso2.transport.http.netty.contract.websocket.WebSocketTextMessage;
import org.wso2.transport.http.netty.contractimpl.websocket.message.DefaultWebSocketCloseMessage;
import org.wso2.transport.http.netty.contractimpl.websocket.message.DefaultWebSocketControlMessage;
import org.wso2.transport.http.netty.exception.UnknownWebSocketFrameTypeException;
import org.wso2.transport.http.netty.internal.websocket.WebSocketUtil;

import java.net.InetSocketAddress;

/**
 * Abstract WebSocket frame handler for WebSocket server and client.
 */
public abstract class WebSocketInboundFrameHandler extends ChannelInboundHandlerAdapter {

    protected Logger log = LoggerFactory.getLogger(WebSocketInboundFrameHandler.class);

    protected final WebSocketConnectorFuture connectorFuture;
    protected final boolean isServer;
    protected final boolean securedConnection;
    protected final String target;
    protected final String interfaceId;
    protected DefaultWebSocketConnection webSocketConnection;
    protected ChannelHandlerContext ctx;
    protected boolean caughtException;
    protected ChannelPromise closePromise;
    protected boolean closeFrameReceived;
    private WebSocketFrameType continuationFrameType;

    public WebSocketInboundFrameHandler(WebSocketConnectorFuture connectorFuture, boolean isServer,
                                        boolean securedConnection, String target, String interfaceId) {
        this.connectorFuture = connectorFuture;
        this.isServer = isServer;
        this.securedConnection = securedConnection;
        this.target = target;
        this.interfaceId = interfaceId;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws WebSocketConnectorException {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent idleStateEvent = (IdleStateEvent) evt;
            if (idleStateEvent.state() == IdleStateEvent.ALL_IDLE_STATE_EVENT.state()) {
                notifyIdleTimeout();
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws WebSocketConnectorException {
        if (!caughtException && webSocketConnection != null && !this.isCloseFrameReceived() && closePromise == null) {
            // Notify abnormal closure.
            DefaultWebSocketMessage webSocketCloseMessage =
                    new DefaultWebSocketCloseMessage(Constants.WEBSOCKET_STATUS_CODE_ABNORMAL_CLOSURE);
            setupCommonProperties(webSocketCloseMessage);
            connectorFuture.notifyWSListener((WebSocketCloseMessage) webSocketCloseMessage);
            return;
        }

        if (closePromise != null && !closePromise.isDone()) {
            String errMsg = "Connection is closed by remote endpoint without echoing a close frame";
            ctx.close().addListener(closeFuture -> closePromise.setFailure(new IllegalStateException(errMsg)));
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof WebSocketFrame)) {
            log.error("Expecting WebSocketFrame. Unknown type.");
            throw new UnknownWebSocketFrameTypeException("Expecting WebSocketFrame. Unknown type.");
        }

        // If the continuation of frames are not following the protocol, netty handles them internally.
        // Hence those situations are not handled here.
        if (msg instanceof TextWebSocketFrame) {
            TextWebSocketFrame textFrame = (TextWebSocketFrame) msg;
            if (!textFrame.isFinalFragment()) {
                continuationFrameType = WebSocketFrameType.TEXT;
            }
            notifyTextMessage(textFrame, textFrame.text(), textFrame.isFinalFragment());
        } else if (msg instanceof BinaryWebSocketFrame) {
            BinaryWebSocketFrame binaryFrame = (BinaryWebSocketFrame) msg;
            if (!binaryFrame.isFinalFragment()) {
                continuationFrameType = WebSocketFrameType.BINARY;
            }
            notifyBinaryMessage(binaryFrame, binaryFrame.content(), binaryFrame.isFinalFragment());
        } else if (msg instanceof CloseWebSocketFrame) {
            notifyCloseMessage((CloseWebSocketFrame) msg);
        } else if (msg instanceof PingWebSocketFrame) {
            notifyPingMessage((PingWebSocketFrame) msg);
        } else if (msg instanceof PongWebSocketFrame) {
            notifyPongMessage((PongWebSocketFrame) msg);
        } else if (msg instanceof ContinuationWebSocketFrame) {
            ContinuationWebSocketFrame frame = (ContinuationWebSocketFrame) msg;
            switch (continuationFrameType) {
                case TEXT:
                    notifyTextMessage(frame, frame.text(), frame.isFinalFragment());
                    break;
                case BINARY:
                    notifyBinaryMessage(frame, frame.content(), frame.isFinalFragment());
                    break;
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws WebSocketConnectorException {
        caughtException = true;
        if (!(cause instanceof CorruptedFrameException)) {
            ChannelFuture closeFrameFuture = ctx.channel().writeAndFlush(new CloseWebSocketFrame(
                    Constants.WEBSOCKET_STATUS_CODE_UNEXPECTED_CONDITION, "Encountered an unexpected condition"));
            closeFrameFuture.addListener(future -> ctx.close().addListener(
                    closeFuture -> connectorFuture.notifyWSListener(webSocketConnection, cause)));
            return;
        }
        connectorFuture.notifyWSListener(webSocketConnection, cause);
    }

    /**
     * Set channel promise for WebSocket connection close.
     *
     * @param closePromise {@link ChannelPromise} to indicate the receiving of close frame echo
     *                                      back from the remote endpoint.
     */
    public void setClosePromise(ChannelPromise closePromise) {
        this.closePromise = closePromise;
    }

    /**
     * Retrieve the WebSocket connection associated with the frame handler.
     *
     * @return the WebSocket connection associated with the frame handler.
     */
    public DefaultWebSocketConnection getWebSocketConnection() {
        return this.webSocketConnection;
    }

    /**
     * Check whether a close frame is received without the relevant connection to this Frame handler sending a close
     * frame.
     *
     * @return true if a close frame is received without the relevant connection to this Frame handler sending a close
     * frame.
     */
    public boolean isCloseFrameReceived() {
        return closeFrameReceived;
    }

    /**
     * Retrieve the {@link ChannelHandlerContext} of the {@link WebSocketInboundFrameHandler}.
     *
     * @return the {@link ChannelHandlerContext} of the {@link WebSocketInboundFrameHandler}.
     */
    public ChannelHandlerContext getChannelHandlerContext() {
        return ctx;
    }

    protected void notifyTextMessage(WebSocketFrame frame, String text, boolean finalFragment)
            throws WebSocketConnectorException {
        DefaultWebSocketMessage webSocketTextMessage = WebSocketUtil.getWebSocketMessage(frame, text, finalFragment);
        setupCommonProperties(webSocketTextMessage);
        connectorFuture.notifyWSListener((WebSocketTextMessage) webSocketTextMessage);
    }

    protected void notifyBinaryMessage(WebSocketFrame frame, ByteBuf content, boolean finalFragment)
            throws WebSocketConnectorException {
        DefaultWebSocketMessage webSocketBinaryMessage = WebSocketUtil.getWebSocketMessage(frame, content,
                finalFragment);
        setupCommonProperties(webSocketBinaryMessage);
        connectorFuture.notifyWSListener((WebSocketBinaryMessage) webSocketBinaryMessage);
    }

    protected void notifyCloseMessage(CloseWebSocketFrame closeWebSocketFrame) throws WebSocketConnectorException {
        String reasonText = closeWebSocketFrame.reasonText();
        int statusCode = closeWebSocketFrame.statusCode();
        // closePromise == null means that WebSocketConnection has not yet initiated a connection closure.
        if (closePromise == null) {
            DefaultWebSocketMessage webSocketCloseMessage = new DefaultWebSocketCloseMessage(statusCode, reasonText);
            setupCommonProperties(webSocketCloseMessage);
            closeFrameReceived = true;
            connectorFuture.notifyWSListener((WebSocketCloseMessage) webSocketCloseMessage);
        } else {
            if (webSocketConnection.getCloseInitiatedStatusCode() != closeWebSocketFrame.statusCode()) {
                String errMsg = String.format(
                        "Expected status code %d but found %d in echoed close frame from remote endpoint",
                        webSocketConnection.getCloseInitiatedStatusCode(), closeWebSocketFrame.statusCode());
                closePromise.setFailure(new IllegalStateException(errMsg));
                return;
            }
            closePromise.setSuccess();
        }
        closeWebSocketFrame.release();
    }

    protected void notifyPingMessage(PingWebSocketFrame pingWebSocketFrame) throws WebSocketConnectorException {
        WebSocketControlMessage webSocketControlMessage = WebSocketUtil.
                getWebSocketControlMessage(pingWebSocketFrame, WebSocketControlSignal.PING);
        setupCommonProperties((DefaultWebSocketMessage) webSocketControlMessage);
        connectorFuture.notifyWSListener(webSocketControlMessage);
    }

    protected void notifyPongMessage(PongWebSocketFrame pongWebSocketFrame) throws WebSocketConnectorException {
        WebSocketControlMessage webSocketControlMessage = WebSocketUtil.
                getWebSocketControlMessage(pongWebSocketFrame, WebSocketControlSignal.PONG);
        setupCommonProperties((DefaultWebSocketMessage) webSocketControlMessage);
        connectorFuture.notifyWSListener(webSocketControlMessage);
    }

    protected void notifyIdleTimeout() throws WebSocketConnectorException {
        DefaultWebSocketMessage webSocketControlMessage = new DefaultWebSocketControlMessage(
                WebSocketControlSignal.IDLE_TIMEOUT, null);
        setupCommonProperties(webSocketControlMessage);
        connectorFuture.notifyWSIdleTimeout((WebSocketControlMessage) webSocketControlMessage);
    }

    protected void setupCommonProperties(DefaultWebSocketMessage webSocketMessage) {
        webSocketMessage.setTarget(target);
        webSocketMessage.setListenerInterface(interfaceId);
        webSocketMessage.setIsConnectionSecured(securedConnection);
        webSocketMessage.setWebSocketConnection(webSocketConnection);
        webSocketMessage.setSessionlID(webSocketConnection.getId());

        webSocketMessage.setProperty(Constants.LISTENER_PORT,
                ((InetSocketAddress) ctx.channel().localAddress()).getPort());
        webSocketMessage.setProperty(Constants.LOCAL_ADDRESS, ctx.channel().localAddress());
        webSocketMessage.setProperty(
                Constants.LOCAL_NAME, ((InetSocketAddress) ctx.channel().localAddress()).getHostName());
    }

}
