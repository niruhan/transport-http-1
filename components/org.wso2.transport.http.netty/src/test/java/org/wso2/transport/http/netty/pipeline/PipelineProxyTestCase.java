/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.transport.http.netty.pipeline;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.wso2.carbon.messaging.exceptions.ServerConnectorException;
import org.wso2.transport.http.netty.config.ListenerConfiguration;
import org.wso2.transport.http.netty.config.SenderConfiguration;
import org.wso2.transport.http.netty.contract.ServerConnector;
import org.wso2.transport.http.netty.contract.ServerConnectorFuture;
import org.wso2.transport.http.netty.contractimpl.HttpWsConnectorFactoryImpl;
import org.wso2.transport.http.netty.listener.ServerBootstrapConfiguration;
import org.wso2.transport.http.netty.passthrough.PassthroughMessageProcessorListener;
import org.wso2.transport.http.netty.util.TestUtil;
import org.wso2.transport.http.netty.util.client.http.HttpClient;
import org.wso2.transport.http.netty.util.server.HttpServer;
import org.wso2.transport.http.netty.util.server.initializers.SendChannelIDServerInitializer;

import java.util.HashMap;
import java.util.LinkedList;

import static org.testng.Assert.assertEquals;

/**
 * Tests for request pipeline implementation.
 */
public class PipelineProxyTestCase {

    private static Logger logger = LoggerFactory.getLogger(PipelineProxyTestCase.class);

    private HttpServer httpServer;
    private ServerConnector serverConnector;

    @BeforeClass
    public void setup() {
        httpServer = TestUtil
                .startHTTPServer(TestUtil.HTTP_SERVER_PORT, new SendChannelIDServerInitializer(2500));

        ListenerConfiguration listenerConfiguration = new ListenerConfiguration();
        listenerConfiguration.setPort(TestUtil.SERVER_CONNECTOR_PORT);
        HttpWsConnectorFactoryImpl httpConnectorFactory = new HttpWsConnectorFactoryImpl();
        serverConnector = httpConnectorFactory.createServerConnector(new ServerBootstrapConfiguration(new HashMap<>()),
                listenerConfiguration);
        ServerConnectorFuture serverConnectorFuture = serverConnector.start();
        serverConnectorFuture.setHttpConnectorListener(
                new PassthroughMessageProcessorListener(new SenderConfiguration()));
        try {
            serverConnectorFuture.sync();
        } catch (InterruptedException e) {
            logger.warn("Interrupted while waiting for server connector to start");
        }
    }

    @Test ()
    public void pipelineProxyTestCase() {
        try {
            HttpClient httpClient = new HttpClient(TestUtil.TEST_HOST, TestUtil.SERVER_CONNECTOR_PORT);
            FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,
                    HttpMethod.POST, "/", Unpooled.wrappedBuffer(TestUtil.smallEntity.getBytes()));
            LinkedList<FullHttpResponse> fullHttpResponses = httpClient.sendTwoInPipeline(httpRequest);

            assertEquals(HttpResponseStatus.OK, fullHttpResponses.pop().status());
            assertEquals(HttpResponseStatus.OK, fullHttpResponses.pop().status());
        } catch (Exception e) {
            TestUtil.handleException("IOException occurred while running pipelineProxyTestCase", e);
        }
    }

    @AfterClass
    public void cleanUp() throws ServerConnectorException {
        serverConnector.stop();
        TestUtil.cleanUp(new LinkedList<>(), httpServer);
    }
}