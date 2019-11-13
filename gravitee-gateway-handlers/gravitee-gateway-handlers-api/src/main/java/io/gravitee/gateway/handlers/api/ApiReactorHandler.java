/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.gateway.handlers.api;

import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.Invoker;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.proxy.ProxyResponse;
import io.gravitee.gateway.core.endpoint.lifecycle.GroupLifecyleManager;
import io.gravitee.gateway.core.invoker.EndpointInvoker;
import io.gravitee.gateway.core.processor.ProcessorFailure;
import io.gravitee.gateway.core.processor.StreamableProcessor;
import io.gravitee.gateway.core.proxy.EmptyProxyResponse;
import io.gravitee.gateway.handlers.api.definition.Api;
import io.gravitee.gateway.handlers.api.processor.OnErrorProcessorChainFactory;
import io.gravitee.gateway.handlers.api.processor.RequestProcessorChainFactory;
import io.gravitee.gateway.handlers.api.processor.ResponseProcessorChainFactory;
import io.gravitee.gateway.policy.PolicyManager;
import io.gravitee.gateway.reactor.Reactable;
import io.gravitee.gateway.reactor.handler.AbstractReactorHandler;
import io.gravitee.gateway.resource.ResourceLifecycleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class ApiReactorHandler extends AbstractReactorHandler implements InitializingBean {

    private final Logger logger = LoggerFactory.getLogger(ApiReactorHandler.class);

    @Autowired
    protected Api api;

    /**
     * Invoker is the connector to access the remote backend / endpoint.
     * If not override by a policy, default invoker is {@link EndpointInvoker}.
     */
    @Autowired
    private Invoker invoker;

    private String contextPath;

    @Autowired
    private RequestProcessorChainFactory requestProcessorChain;

    @Autowired
    private ResponseProcessorChainFactory responseProcessorChain;

    @Autowired
    private OnErrorProcessorChainFactory errorProcessorChain;

    @Override
    protected void doHandle(final ExecutionContext context) {
        final Request request = context.request();

        // Pause the request and resume it as soon as all the stream are plugged and we have processed the HEAD part
        // of the request. (see handleProxyInvocation method).
        request.pause();

        context.setAttribute(ExecutionContext.ATTR_CONTEXT_PATH, request.contextPath());
        context.setAttribute(ExecutionContext.ATTR_API, api.getId());
        context.setAttribute(ExecutionContext.ATTR_INVOKER, invoker);

        // Prepare request metrics
        request.metrics().setApi(api.getId());
        request.metrics().setPath(request.pathInfo());

        // It's time to process incoming client request
        handleClientRequest(context);
    }

    private void handleClientRequest(final ExecutionContext context) {
        final StreamableProcessor<ExecutionContext, Buffer> chain = requestProcessorChain.create();

        chain
                .handler(__ -> handleProxyInvocation(context, chain))
                .streamErrorHandler(failure -> {
                    handleError(context, failure);
                })
                .errorHandler(failure -> {
                    handleError(context, failure);
                })
                .exitHandler(__ -> {
                    context.request().resume();
                    handler.handle(context);
                })
                .handle(context);
    }

    private void handleProxyInvocation(
            final ExecutionContext context,
            final StreamableProcessor<ExecutionContext, Buffer> chain) {

        // Call an invoker to get a proxy connection (connection to an underlying backend, mainly HTTP)
        Invoker upstreamInvoker = (Invoker) context.getAttribute(ExecutionContext.ATTR_INVOKER);

        context.request().metrics().setApiResponseTimeMs(System.currentTimeMillis());

        upstreamInvoker.invoke(context, chain, connection -> {
            connection.responseHandler(proxyResponse -> handleProxyResponse(context, proxyResponse));

            // Override the stream error handler to be able to cancel connection to backend
            chain.streamErrorHandler(failure -> {
                connection.cancel();
                handleError(context, failure);
            });
        });

        // Plug server request stream to request processor stream
        context.request().bodyHandler(chain::write);

        if (context.request().ended()) {
            // since Vert.x 3.6.0 it can happen that requests without body (e.g. a GET) are ended even while in paused-State
            // Setting the endHandler would then lead to an Exception
            // see also https://github.com/eclipse-vertx/vert.x/issues/2763
            // so we now check if the request already is ended before installing an endHandler
            chain.end();
        } else {
            context.request().endHandler(result -> chain.end());
        }
    }

    private void handleProxyResponse(final ExecutionContext context, final ProxyResponse proxyResponse) {
        if (proxyResponse == null || proxyResponse instanceof EmptyProxyResponse) {
            context.response().status((proxyResponse == null) ? HttpStatusCode.SERVICE_UNAVAILABLE_503 : proxyResponse.status());
            context.request().metrics().setApiResponseTimeMs(System.currentTimeMillis() -
                    context.request().metrics().getApiResponseTimeMs());
            handler.handle(context);
        } else {
            handleClientResponse(context, proxyResponse);
        }
    }

    private void handleClientResponse(final ExecutionContext context, final ProxyResponse proxyResponse) {
        // Set the status
        context.response().status(proxyResponse.status());

        // Copy HTTP headers
        proxyResponse.headers().forEach((headerName, headerValues) -> context.response().headers().put(headerName, headerValues));

        final StreamableProcessor<ExecutionContext, Buffer> chain = responseProcessorChain.create();

        chain
                .errorHandler(failure -> {
                    handleError(context, failure);
                })
                .streamErrorHandler(failure -> {
                    handleError(context, failure);
                })
                .exitHandler(__ -> handler.handle(context))
                .handler(stream -> {
                    chain
                            .bodyHandler(chunk -> context.response().write(chunk))
                            .endHandler(__ -> handler.handle(context));

                    proxyResponse
                            .bodyHandler(buffer -> {
                                chain.write(buffer);

                                if (context.response().writeQueueFull()) {
                                    proxyResponse.pause();
                                    context.response().drainHandler(aVoid -> proxyResponse.resume());
                                }
                            }).endHandler(__ -> {
                                context.request().metrics().setApiResponseTimeMs(System.currentTimeMillis() -
                                    context.request().metrics().getApiResponseTimeMs());
                                chain.end();
                            });

                    // Resume response read
                    proxyResponse.resume();
                })
                .handle(context);
    }


    private void handleError(ExecutionContext context, ProcessorFailure failure) {
        if (context.request().metrics().getApiResponseTimeMs() > 0) {
            context.request().metrics().setApiResponseTimeMs(System.currentTimeMillis() -
                    context.request().metrics().getApiResponseTimeMs());
        }
        context.setAttribute(ExecutionContext.ATTR_PREFIX + "failure", failure);
        errorProcessorChain
                .create()
                .handler(__ -> handler.handle(context))
                .errorHandler(__ -> handler.handle(context))
                .handle(context);
    }

    @Override
    public void afterPropertiesSet() {
        contextPath = reactable().contextPath() + '/';
    }

    @Override
    public String contextPath() {
        return contextPath;
    }

    @Override
    public Reactable reactable() {
        return api;
    }

    @Override
    protected void doStart() throws Exception {
        logger.info("API handler is now starting, preparing API context...");
        long startTime = System.currentTimeMillis(); // Get the start Time
        super.doStart();

        // Start resources before
        applicationContext.getBean(ResourceLifecycleManager.class).start();
        applicationContext.getBean(PolicyManager.class).start();
        applicationContext.getBean(GroupLifecyleManager.class).start();

        long endTime = System.currentTimeMillis(); // Get the end Time
        logger.info("API handler started in {} ms and now ready to accept requests on {}/*",
                (endTime - startTime), api.getProxy().getContextPath());
    }

    @Override
    protected void doStop() throws Exception {
        logger.info("API handler is now stopping, closing context...");

        applicationContext.getBean(PolicyManager.class).stop();
        applicationContext.getBean(ResourceLifecycleManager.class).stop();
        applicationContext.getBean(GroupLifecyleManager.class).stop();

        super.doStop();
        logger.info("API handler is now stopped", api);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("ApiReactorHandler{");
        sb.append("contextPath=").append(api.getProxy().getContextPath());
        sb.append('}');
        return sb.toString();
    }
}
