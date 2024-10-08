/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.rest.handler.router;

import org.apache.flink.runtime.rest.handler.AbstractRestHandler;
import org.apache.flink.runtime.rest.handler.util.HandlerUtils;
import org.apache.flink.runtime.rest.messages.ErrorResponseBody;

import org.apache.flink.shaded.netty4.io.netty.channel.ChannelHandler;
import org.apache.flink.shaded.netty4.io.netty.channel.ChannelHandlerContext;
import org.apache.flink.shaded.netty4.io.netty.channel.ChannelInboundHandler;
import org.apache.flink.shaded.netty4.io.netty.channel.ChannelPipeline;
import org.apache.flink.shaded.netty4.io.netty.channel.SimpleChannelInboundHandler;
import org.apache.flink.shaded.netty4.io.netty.handler.codec.http.DefaultFullHttpResponse;
import org.apache.flink.shaded.netty4.io.netty.handler.codec.http.HttpHeaders;
import org.apache.flink.shaded.netty4.io.netty.handler.codec.http.HttpMethod;
import org.apache.flink.shaded.netty4.io.netty.handler.codec.http.HttpRequest;
import org.apache.flink.shaded.netty4.io.netty.handler.codec.http.HttpResponseStatus;
import org.apache.flink.shaded.netty4.io.netty.handler.codec.http.HttpVersion;
import org.apache.flink.shaded.netty4.io.netty.handler.codec.http.QueryStringDecoder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Inbound handler that converts HttpRequest to Routed and passes Routed to the matched handler.
 *
 * <p>This class replaces the standard error response to be identical with those sent by the {@link
 * AbstractRestHandler}.
 *
 * <p>This class is based on:
 * https://github.com/sinetja/netty-router/blob/1.10/src/main/java/io/netty/handler/codec/http/router/AbstractHandler.java
 * https://github.com/sinetja/netty-router/blob/1.10/src/main/java/io/netty/handler/codec/http/router/Handler.java
 * clouding:
 *          1. 通过初始化handler注册到router的信息,找到http请求对应的路由结果
 *          2. 如果路由为空,这返回Not Found, 404
 *          3. 如果有,则触发handler处理消息
 */
public class RouterHandler extends SimpleChannelInboundHandler<HttpRequest> {
    private static final String ROUTER_HANDLER_NAME =
            RouterHandler.class.getName() + "_ROUTER_HANDLER";
    private static final String ROUTED_HANDLER_NAME =
            RouterHandler.class.getName() + "_ROUTED_HANDLER";

    private static final Logger LOG = LoggerFactory.getLogger(RouterHandler.class);

    private final Map<String, String> responseHeaders;
    private final Router router;

    public RouterHandler(Router router, final Map<String, String> responseHeaders) {
        this.router = requireNonNull(router);
        this.responseHeaders = requireNonNull(responseHeaders);
    }

    public String getName() {
        return ROUTER_HANDLER_NAME;
    }

    // clouding 注释: 2022/3/13 18:43
    //          处理消息
    @Override
    protected void channelRead0(
            ChannelHandlerContext channelHandlerContext, HttpRequest httpRequest) {
        if (HttpHeaders.is100ContinueExpected(httpRequest)) {
            channelHandlerContext.writeAndFlush(
                    new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE));
            return;
        }

        // Route
        HttpMethod method = httpRequest.getMethod();
        QueryStringDecoder qsd = new QueryStringDecoder(httpRequest.uri());
        RouteResult<?> routeResult = router.route(method, qsd.path(), qsd.parameters());

        if (routeResult == null) {
            // clouding 注释: 2022/3/13 18:44
            //          404, Not Found
            respondNotFound(channelHandlerContext, httpRequest);
            return;
        }

        routed(channelHandlerContext, routeResult, httpRequest);
    }

    private void routed(
            ChannelHandlerContext channelHandlerContext,
            RouteResult<?> routeResult,
            HttpRequest httpRequest) {
        ChannelInboundHandler handler = (ChannelInboundHandler) routeResult.target();

        // The handler may have been added (keep alive)
        ChannelPipeline pipeline = channelHandlerContext.pipeline();
        ChannelHandler addedHandler = pipeline.get(ROUTED_HANDLER_NAME);
        if (handler != addedHandler) {
            if (addedHandler == null) {
                pipeline.addAfter(ROUTER_HANDLER_NAME, ROUTED_HANDLER_NAME, handler);
            } else {
                pipeline.replace(addedHandler, ROUTED_HANDLER_NAME, handler);
            }
        }

        RoutedRequest<?> request = new RoutedRequest<>(routeResult, httpRequest);
        channelHandlerContext.fireChannelRead(request.retain());
    }

    private void respondNotFound(ChannelHandlerContext channelHandlerContext, HttpRequest request) {
        LOG.trace(
                "Request could not be routed to any handler. Uri:{} Method:{}",
                request.getUri(),
                request.getMethod());
        HandlerUtils.sendErrorResponse(
                channelHandlerContext,
                request,
                new ErrorResponseBody("Not found."),
                HttpResponseStatus.NOT_FOUND,
                responseHeaders);
    }
}
