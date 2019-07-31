/*
 * Copyright (c) 2011-2017 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */

package io.vertx.core.http.impl;

import io.vertx.core.Closeable;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.VertxException;
import io.vertx.core.*;
import io.vertx.core.http.*;
import io.vertx.core.impl.ContextInternal;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.core.net.ProxyOptions;
import io.vertx.core.net.ProxyType;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.net.impl.SSLHelper;
import io.vertx.core.spi.metrics.HttpClientMetrics;
import io.vertx.core.spi.metrics.Metrics;
import io.vertx.core.spi.metrics.MetricsProvider;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 *
 * This class is thread-safe
 *
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class HttpClientImpl implements HttpClient, MetricsProvider {

  private final Function<HttpClientResponse, Future<HttpClientRequest>> DEFAULT_HANDLER = resp -> {
    try {
      int statusCode = resp.statusCode();
      String location = resp.getHeader(HttpHeaders.LOCATION);
      if (location != null && (statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307 || statusCode == 308)) {
        HttpMethod m = resp.request().method();
        if (statusCode == 303) {
          m = HttpMethod.GET;
        } else if (m != HttpMethod.GET && m != HttpMethod.HEAD) {
          return null;
        }
        URI uri = HttpUtils.resolveURIReference(resp.request().absoluteURI(), location);
        boolean ssl;
        int port = uri.getPort();
        String protocol = uri.getScheme();
        char chend = protocol.charAt(protocol.length() - 1);
        if (chend == 'p') {
          ssl = false;
          if (port == -1) {
            port = 80;
          }
        } else if (chend == 's') {
          ssl = true;
          if (port == -1) {
            port = 443;
          }
        } else {
          return null;
        }
        String requestURI = uri.getPath();
        String query = uri.getQuery();
        if (query != null) {
          requestURI += "?" + query;
        }
        return Future.succeededFuture(createRequest(m, null, uri.getHost(), port, ssl, requestURI, null));
      }
      return null;
    } catch (Exception e) {
      return Future.failedFuture(e);
    }
  };

  private static final Logger log = LoggerFactory.getLogger(HttpClientImpl.class);

  private final VertxInternal vertx;
  private final HttpClientOptions options;
  private final ContextInternal creatingContext;
  private final ConnectionManager websocketCM; // The queue manager for websockets
  private final ConnectionManager httpCM; // The queue manager for requests
  private final Closeable closeHook;
  private final ProxyType proxyType;
  private final SSLHelper sslHelper;
  private final HttpClientMetrics metrics;
  private final boolean keepAlive;
  private final boolean pipelining;
  private volatile boolean closed;
  private volatile Handler<HttpConnection> connectionHandler;
  private volatile Function<HttpClientResponse, Future<HttpClientRequest>> redirectHandler = DEFAULT_HANDLER;

  public HttpClientImpl(VertxInternal vertx, HttpClientOptions options) {
    this.vertx = vertx;
    this.metrics = vertx.metricsSPI() != null ? vertx.metricsSPI().createHttpClientMetrics(options) : null;
    this.options = new HttpClientOptions(options);
    List<HttpVersion> alpnVersions = options.getAlpnVersions();
    if (alpnVersions == null || alpnVersions.isEmpty()) {
      switch (options.getProtocolVersion()) {
        case HTTP_2:
          alpnVersions = Arrays.asList(HttpVersion.HTTP_2, HttpVersion.HTTP_1_1);
          break;
        default:
          alpnVersions = Collections.singletonList(options.getProtocolVersion());
          break;
      }
    }
    this.keepAlive = options.isKeepAlive();
    this.pipelining = options.isPipelining();
    this.sslHelper = new SSLHelper(options, options.getKeyCertOptions(), options.getTrustOptions()).
        setApplicationProtocols(alpnVersions);
    sslHelper.validate(vertx);
    this.creatingContext = vertx.getContext();
    closeHook = completionHandler -> {
      HttpClientImpl.this.close();
      completionHandler.handle(Future.succeededFuture());
    };
    if (creatingContext != null) {
      if(options.getProtocolVersion() == HttpVersion.HTTP_2 && Context.isOnWorkerThread()) {
        throw new IllegalStateException("Cannot use HttpClient with HTTP_2 in a worker");
      }
      creatingContext.addCloseHook(closeHook);
    }
    if (!keepAlive && pipelining) {
      throw new IllegalStateException("Cannot have pipelining with no keep alive");
    }
    long maxWeight = options.getMaxPoolSize() * options.getHttp2MaxPoolSize();
    websocketCM = new ConnectionManager(this, metrics, HttpVersion.HTTP_1_1, maxWeight, options.getMaxWaitQueueSize());

    httpCM = new ConnectionManager(this, metrics, options.getProtocolVersion(), maxWeight, options.getMaxWaitQueueSize());
    proxyType = options.getProxyOptions() != null ? options.getProxyOptions().getType() : null;
    httpCM.start();
    websocketCM.start();
  }

  HttpClientMetrics metrics() {
    return metrics;
  }

  @Override
  public void webSocket(WebSocketConnectOptions connectOptions, Handler<AsyncResult<WebSocket>> handler) {
    ContextInternal ctx = vertx.getOrCreateContext();
    SocketAddress addr = SocketAddress.inetSocketAddress(connectOptions.getPort(), connectOptions.getHost());
    websocketCM.getConnection(
      ctx,
      addr,
      connectOptions.isSsl() != null ? connectOptions.isSsl() : options.isSsl(),
      addr, ar -> {
        if (ar.succeeded()) {
          Http1xClientConnection conn = (Http1xClientConnection) ar.result();
          conn.toWebSocket(connectOptions.getURI(), connectOptions.getHeaders(), connectOptions.getVersion(), connectOptions.getSubProtocols(), HttpClientImpl.this.options.getMaxWebsocketFrameSize(), handler);
        } else {
          ctx.schedule(v -> handler.handle(Future.failedFuture(ar.cause())));
        }
      });
  }

  @Override
  public Future<WebSocket> webSocket(int port, String host, String requestURI) {
    Promise<WebSocket> promise = Promise.promise();
    webSocket(port, host, requestURI, promise);
    return promise.future();
  }

  @Override
  public Future<WebSocket> webSocket(String host, String requestURI) {
    Promise<WebSocket> promise = Promise.promise();
    webSocket(host, requestURI, promise);
    return promise.future();
  }

  @Override
  public Future<WebSocket> webSocket(String requestURI) {
    Promise<WebSocket> promise = Promise.promise();
    webSocket(requestURI, promise);
    return promise.future();
  }

  @Override
  public Future<WebSocket> webSocket(WebSocketConnectOptions options) {
    Promise<WebSocket> promise = Promise.promise();
    webSocket(options, promise);
    return promise.future();
  }

  @Override
  public Future<WebSocket> webSocketAbs(String url, MultiMap headers, WebsocketVersion version, List<String> subProtocols) {
    Promise<WebSocket> promise = Promise.promise();
    webSocketAbs(url, headers, version, subProtocols, promise);
    return promise.future();
  }

  @Override
  public void webSocket(int port, String host, String requestURI, Handler<AsyncResult<WebSocket>> handler) {
    webSocket(new WebSocketConnectOptions().setURI(requestURI).setHost(host).setPort(port), handler);
  }

  @Override
  public void webSocket(String host, String requestURI, Handler<AsyncResult<WebSocket>> handler) {
    webSocket(options.getDefaultPort(), host, requestURI, handler);
  }

  @Override
  public void webSocket(String requestURI, Handler<AsyncResult<WebSocket>> handler) {
    webSocket(options.getDefaultPort(), options.getDefaultHost(), requestURI, handler);
  }

  @Override
  public void webSocketAbs(String url, MultiMap headers, WebsocketVersion version, List<String> subProtocols, Handler<AsyncResult<WebSocket>> handler) {
    URI uri;
    try {
      uri = new URI(url);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(e);
    }
    String scheme = uri.getScheme();
    if (!"ws".equals(scheme) && !"wss".equals(scheme)) {
      throw new IllegalArgumentException("Scheme: " + scheme);
    }
    boolean ssl = scheme.length() == 3;
    int port = uri.getPort();
    if (port == -1) port = ssl ? 443 : 80;
    StringBuilder relativeUri = new StringBuilder();
    if (uri.getRawPath() != null) {
      relativeUri.append(uri.getRawPath());
    }
    if (uri.getRawQuery() != null) {
      relativeUri.append('?').append(uri.getRawQuery());
    }
    if (uri.getRawFragment() != null) {
      relativeUri.append('#').append(uri.getRawFragment());
    }
    WebSocketConnectOptions options = new WebSocketConnectOptions()
      .setHost(uri.getHost())
      .setPort(port).setSsl(ssl)
      .setURI(relativeUri.toString())
      .setHeaders(headers)
      .setVersion(version)
      .setSubProtocols(subProtocols);
    webSocket(options, handler);
  }

  @Override
  public HttpClientRequest requestAbs(HttpMethod method, String absoluteURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return requestAbs(method, null, absoluteURI, responseHandler);
  }

  @Override
  public HttpClientRequest requestAbs(HttpMethod method, SocketAddress serverAddress, String absoluteURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    Objects.requireNonNull(responseHandler, "no null responseHandler accepted");
    return requestAbs(method, serverAddress, absoluteURI).setHandler(responseHandler);
  }

  @Override
  public HttpClientRequest get(RequestOptions options) {
    return request(HttpMethod.GET, options);
  }

  @Override
  public HttpClientRequest request(HttpMethod method, int port, String host, String requestURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    Objects.requireNonNull(responseHandler, "no null responseHandler accepted");
    return request(method, port, host, requestURI).setHandler(responseHandler);
  }

  @Override
  public HttpClientRequest request(HttpMethod method, SocketAddress serverAddress, int port, String host, String requestURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    Objects.requireNonNull(responseHandler, "no null responseHandler accepted");
    return request(method, serverAddress, port, host, requestURI).setHandler(responseHandler);
  }

  @Override
  public HttpClientRequest request(HttpMethod method, String host, String requestURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return request(method, options.getDefaultPort(), host, requestURI, responseHandler);
  }

  @Override
  public HttpClientRequest request(HttpMethod method, String requestURI) {
    return request(method, options.getDefaultPort(), options.getDefaultHost(), requestURI);
  }

  @Override
  public HttpClientRequest request(HttpMethod method, String requestURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return request(method, options.getDefaultPort(), options.getDefaultHost(), requestURI, responseHandler);
  }

  @Override
  public HttpClientRequest requestAbs(HttpMethod method, String absoluteURI) {
    return requestAbs(method, null, absoluteURI);
  }

  @Override
  public HttpClientRequest requestAbs(HttpMethod method, SocketAddress serverAddress, String absoluteURI) {
    URL url = parseUrl(absoluteURI);
    Boolean ssl = false;
    int port = url.getPort();
    String relativeUri = url.getPath().isEmpty() ? "/" + url.getFile() : url.getFile();
    String protocol = url.getProtocol();
    if ("ftp".equals(protocol)) {
      if (port == -1) {
        port = 21;
      }
    } else {
      char chend = protocol.charAt(protocol.length() - 1);
      if (chend == 'p') {
        if (port == -1) {
          port = 80;
        }
      } else if (chend == 's'){
        ssl = true;
        if (port == -1) {
          port = 443;
        }
      }
    }
    // if we do not know the protocol, the port still may be -1, we will handle that below
    return createRequest(method, serverAddress, protocol, url.getHost(), port, ssl, relativeUri, null);
  }

  @Override
  public HttpClientRequest request(HttpMethod method, int port, String host, String requestURI) {
    return createRequest(method, null, host, port, null, requestURI, null);
  }

  @Override
  public HttpClientRequest request(HttpMethod method, SocketAddress serverAddress, int port, String host, String requestURI) {
    return createRequest(method, serverAddress, host, port, null, requestURI, null);
  }

  @Override
  public HttpClientRequest request(HttpMethod method, RequestOptions options, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return request(method, options).setHandler(responseHandler);
  }

  @Override
  public HttpClientRequest request(HttpMethod method, SocketAddress serverAddress, RequestOptions options, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return request(method, serverAddress, options).setHandler(responseHandler);
  }

  @Override
  public HttpClientRequest request(HttpMethod method, SocketAddress serverAddress, RequestOptions options) {
    return createRequest(method, serverAddress, options.getHost(), options.getPort(), options.isSsl(), options.getURI(), null);
  }

  @Override
  public HttpClientRequest request(HttpMethod method, RequestOptions options) {
    return createRequest(method, null, options.getHost(), options.getPort(), options.isSsl(), options.getURI(), options.getHeaders());
  }

  @Override
  public HttpClientRequest request(HttpMethod method, String host, String requestURI) {
    return request(method, options.getDefaultPort(), host, requestURI);
  }

  @Override
  public HttpClientRequest get(int port, String host, String requestURI) {
    return request(HttpMethod.GET, port, host, requestURI);
  }

  @Override
  public HttpClientRequest get(String host, String requestURI) {
    return get(options.getDefaultPort(), host, requestURI);
  }

  @Override
  public HttpClientRequest get(RequestOptions options, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return request(HttpMethod.GET, options, responseHandler);
  }

  @Override
  public HttpClientRequest get(int port, String host, String requestURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return request(HttpMethod.GET, port, host, requestURI, responseHandler);
  }

  @Override
  public HttpClientRequest get(String host, String requestURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return get(options.getDefaultPort(), host, requestURI, responseHandler);
  }

  @Override
  public HttpClientRequest get(String requestURI) {
    return request(HttpMethod.GET, requestURI);
  }

  @Override
  public HttpClientRequest get(String requestURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return request(HttpMethod.GET, requestURI, responseHandler);
  }

  @Override
  public HttpClientRequest getAbs(String absoluteURI) {
    return requestAbs(HttpMethod.GET, absoluteURI);
  }

  @Override
  public HttpClientRequest getAbs(String absoluteURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return requestAbs(HttpMethod.GET, absoluteURI, responseHandler);
  }

  @Override
  public HttpClient getNow(RequestOptions options, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return requestNow(HttpMethod.GET, options, responseHandler);
  }

  @Override
  public HttpClient getNow(int port, String host, String requestURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    get(port, host, requestURI, responseHandler).end();
    return this;
  }

  @Override
  public HttpClient getNow(String host, String requestURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return getNow(options.getDefaultPort(), host, requestURI, responseHandler);
  }

  @Override
  public HttpClient getNow(String requestURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    get(requestURI, responseHandler).end();
    return this;
  }

  @Override
  public HttpClientRequest post(RequestOptions options) {
    return request(HttpMethod.POST, options);
  }

  @Override
  public HttpClientRequest post(int port, String host, String requestURI) {
    return request(HttpMethod.POST, port, host, requestURI);
  }

  @Override
  public HttpClientRequest post(String host, String requestURI) {
    return post(options.getDefaultPort(), host, requestURI);
  }

  @Override
  public HttpClientRequest post(RequestOptions options, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return request(HttpMethod.POST, options, responseHandler);
  }

  @Override
  public HttpClientRequest post(int port, String host, String requestURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return request(HttpMethod.POST, port, host, requestURI, responseHandler);
  }

  @Override
  public HttpClientRequest post(String host, String requestURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return post(options.getDefaultPort(), host, requestURI, responseHandler);
  }

  @Override
  public HttpClientRequest post(String requestURI) {
    return request(HttpMethod.POST, requestURI);
  }

  @Override
  public HttpClientRequest post(String requestURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return request(HttpMethod.POST, requestURI, responseHandler);
  }

  @Override
  public HttpClientRequest postAbs(String absoluteURI) {
    return requestAbs(HttpMethod.POST, absoluteURI);
  }

  @Override
  public HttpClientRequest postAbs(String absoluteURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return requestAbs(HttpMethod.POST, absoluteURI, responseHandler);
  }

  @Override
  public HttpClientRequest head(RequestOptions options) {
    return request(HttpMethod.HEAD, options);
  }

  @Override
  public HttpClientRequest head(int port, String host, String requestURI) {
    return request(HttpMethod.HEAD, port, host, requestURI);
  }

  @Override
  public HttpClientRequest head(String host, String requestURI) {
    return head(options.getDefaultPort(), host, requestURI);
  }

  @Override
  public HttpClientRequest head(RequestOptions options, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return request(HttpMethod.HEAD, options, responseHandler);
  }

  @Override
  public HttpClientRequest head(int port, String host, String requestURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return request(HttpMethod.HEAD, port, host, requestURI, responseHandler);
  }

  @Override
  public HttpClientRequest head(String host, String requestURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return head(options.getDefaultPort(), host, requestURI, responseHandler);
  }

  @Override
  public HttpClientRequest head(String requestURI) {
    return request(HttpMethod.HEAD, requestURI);
  }

  @Override
  public HttpClientRequest head(String requestURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return request(HttpMethod.HEAD, requestURI, responseHandler);
  }

  @Override
  public HttpClientRequest headAbs(String absoluteURI) {
    return requestAbs(HttpMethod.HEAD, absoluteURI);
  }

  @Override
  public HttpClientRequest headAbs(String absoluteURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return requestAbs(HttpMethod.HEAD, absoluteURI, responseHandler);
  }

  @Override
  public HttpClient headNow(RequestOptions options, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return requestNow(HttpMethod.HEAD, options, responseHandler);
  }

  @Override
  public HttpClient headNow(int port, String host, String requestURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    head(port, host, requestURI, responseHandler).end();
    return this;
  }

  @Override
  public HttpClient headNow(String host, String requestURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return headNow(options.getDefaultPort(), host, requestURI, responseHandler);
  }

  @Override
  public HttpClient headNow(String requestURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    head(requestURI, responseHandler).end();
    return this;
  }

  @Override
  public HttpClientRequest options(RequestOptions options) {
    return request(HttpMethod.OPTIONS, options);
  }

  @Override
  public HttpClientRequest options(int port, String host, String requestURI) {
    return request(HttpMethod.OPTIONS, port, host, requestURI);
  }

  @Override
  public HttpClientRequest options(String host, String requestURI) {
    return options(options.getDefaultPort(), host, requestURI);
  }

  @Override
  public HttpClientRequest options(RequestOptions options, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return request(HttpMethod.OPTIONS, options, responseHandler);
  }

  @Override
  public HttpClientRequest options(int port, String host, String requestURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return request(HttpMethod.OPTIONS, port, host, requestURI, responseHandler);
  }

  @Override
  public HttpClientRequest options(String host, String requestURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return options(options.getDefaultPort(), host, requestURI, responseHandler);
  }

  @Override
  public HttpClientRequest options(String requestURI) {
    return request(HttpMethod.OPTIONS, requestURI);
  }

  @Override
  public HttpClientRequest options(String requestURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return request(HttpMethod.OPTIONS, requestURI, responseHandler);
  }

  @Override
  public HttpClientRequest optionsAbs(String absoluteURI) {
    return requestAbs(HttpMethod.OPTIONS, absoluteURI);
  }

  @Override
  public HttpClientRequest optionsAbs(String absoluteURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return requestAbs(HttpMethod.OPTIONS, absoluteURI, responseHandler);
  }

  @Override
  public HttpClient optionsNow(RequestOptions options, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return requestNow(HttpMethod.OPTIONS, options, responseHandler);
  }

  @Override
  public HttpClient optionsNow(int port, String host, String requestURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    options(port, host, requestURI, responseHandler).end();
    return this;
  }

  @Override
  public HttpClient optionsNow(String host, String requestURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return optionsNow(options.getDefaultPort(), host, requestURI, responseHandler);
  }

  @Override
  public HttpClient optionsNow(String requestURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    options(requestURI, responseHandler).end();
    return this;
  }

  @Override
  public HttpClientRequest put(RequestOptions options) {
    return request(HttpMethod.PUT, options);
  }

  @Override
  public HttpClientRequest put(int port, String host, String requestURI) {
    return request(HttpMethod.PUT, port, host, requestURI);
  }

  @Override
  public HttpClientRequest put(String host, String requestURI) {
    return put(options.getDefaultPort(), host, requestURI);
  }

  @Override
  public HttpClientRequest put(RequestOptions options, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return request(HttpMethod.PUT, options, responseHandler);
  }

  @Override
  public HttpClientRequest put(int port, String host, String requestURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return request(HttpMethod.PUT, port, host, requestURI, responseHandler);
  }

  @Override
  public HttpClientRequest put(String host, String requestURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return put(options.getDefaultPort(), host, requestURI, responseHandler);
  }

  @Override
  public HttpClientRequest put(String requestURI) {
    return request(HttpMethod.PUT, requestURI);
  }

  @Override
  public HttpClientRequest put(String requestURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return request(HttpMethod.PUT, requestURI, responseHandler);
  }

  @Override
  public HttpClientRequest putAbs(String absoluteURI) {
    return requestAbs(HttpMethod.PUT, absoluteURI);
  }

  @Override
  public HttpClientRequest putAbs(String absoluteURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return requestAbs(HttpMethod.PUT, absoluteURI, responseHandler);
  }

  @Override
  public HttpClientRequest delete(RequestOptions options) {
    return request(HttpMethod.DELETE, options);
  }

  @Override
  public HttpClientRequest delete(int port, String host, String requestURI) {
    return request(HttpMethod.DELETE, port, host, requestURI);
  }

  @Override
  public HttpClientRequest delete(String host, String requestURI) {
    return delete(options.getDefaultPort(), host, requestURI);
  }

  @Override
  public HttpClientRequest delete(RequestOptions options, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return request(HttpMethod.DELETE, options, responseHandler);
  }

  @Override
  public HttpClientRequest delete(int port, String host, String requestURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return request(HttpMethod.DELETE, port, host, requestURI, responseHandler);
  }

  @Override
  public HttpClientRequest delete(String host, String requestURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return delete(options.getDefaultPort(), host, requestURI, responseHandler);
  }

  @Override
  public HttpClientRequest delete(String requestURI) {
    return request(HttpMethod.DELETE, requestURI);
  }

  @Override
  public HttpClientRequest delete(String requestURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return request(HttpMethod.DELETE, requestURI, responseHandler);
  }

  @Override
  public HttpClientRequest deleteAbs(String absoluteURI) {
    return requestAbs(HttpMethod.DELETE, absoluteURI);
  }

  @Override
  public HttpClientRequest deleteAbs(String absoluteURI, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    return requestAbs(HttpMethod.DELETE, absoluteURI, responseHandler);
  }

  @Override
  public void close() {
    synchronized (this) {
      checkClosed();
      closed = true;
    }
    if (creatingContext != null) {
      creatingContext.removeCloseHook(closeHook);
    }
    websocketCM.close();
    httpCM.close();
    if (metrics != null) {
      metrics.close();
    }
  }

  @Override
  public boolean isMetricsEnabled() {
    return getMetrics() != null;
  }

  @Override
  public Metrics getMetrics() {
    return metrics;
  }

  @Override
  public HttpClient connectionHandler(Handler<HttpConnection> handler) {
    connectionHandler = handler;
    return this;
  }

  Handler<HttpConnection> connectionHandler() {
    return connectionHandler;
  }

  @Override
  public HttpClient redirectHandler(Function<HttpClientResponse, Future<HttpClientRequest>> handler) {
    if (handler == null) {
      handler = DEFAULT_HANDLER;
    }
    redirectHandler = handler;
    return this;
  }

  @Override
  public Function<HttpClientResponse, Future<HttpClientRequest>> redirectHandler() {
    return redirectHandler;
  }

  public HttpClientOptions getOptions() {
    return options;
  }

  void getConnectionForRequest(ContextInternal ctx,
                               SocketAddress peerAddress,
                               boolean ssl,
                               SocketAddress server,
                               Handler<AsyncResult<HttpClientStream>> handler) {
    httpCM.getConnection(ctx, peerAddress, ssl, server, ar -> {
      if (ar.succeeded()) {
        ar.result().createStream(ctx, handler);
      } else {
        ctx.dispatch(Future.failedFuture(ar.cause()), handler);
      }
    });
  }

  /**
   * @return the vertx, for use in package related classes only.
   */
  public VertxInternal getVertx() {
    return vertx;
  }

  SSLHelper getSslHelper() {
    return sslHelper;
  }

  private URL parseUrl(String surl) {
    // Note - parsing a URL this way is slower than specifying host, port and relativeURI
    try {
      return new URL(surl);
    } catch (MalformedURLException e) {
      throw new VertxException("Invalid url: " + surl, e);
    }
  }

  private HttpClient requestNow(HttpMethod method, RequestOptions options, Handler<AsyncResult<HttpClientResponse>> responseHandler) {
    createRequest(method, null, options.getHost(), options.getPort(), options.isSsl(), options.getURI(), null).setHandler(responseHandler).end();
    return this;
  }

  private HttpClientRequest createRequest(HttpMethod method, SocketAddress serverAddress, String host, int port, Boolean ssl, String relativeURI, MultiMap headers) {
    return createRequest(method, serverAddress, ssl==null || ssl==false ? "http" : "https", host, port, ssl, relativeURI, headers);
  }

  private HttpClientRequest createRequest(HttpMethod method, SocketAddress server, String protocol, String host, int port, Boolean ssl, String relativeURI, MultiMap headers) {
    Objects.requireNonNull(method, "no null method accepted");
    Objects.requireNonNull(protocol, "no null protocol accepted");
    Objects.requireNonNull(host, "no null host accepted");
    Objects.requireNonNull(relativeURI, "no null relativeURI accepted");
    boolean useAlpn = options.isUseAlpn();
    boolean useSSL = ssl != null ? ssl : options.isSsl();
    if (!useAlpn && useSSL && options.getProtocolVersion() == HttpVersion.HTTP_2) {
      throw new IllegalArgumentException("Must enable ALPN when using H2");
    }
    checkClosed();
    HttpClientRequest req;
    boolean useProxy = !useSSL && proxyType == ProxyType.HTTP;
    if (useProxy) {
      int defaultPort = protocol.equals("ftp") ? 21 : 80;
      String addPort = (port != -1 && port != defaultPort) ? (":" + port) : "";
      relativeURI = protocol + "://" + host + addPort + relativeURI;
      ProxyOptions proxyOptions = options.getProxyOptions();
      if (proxyOptions.getUsername() != null && proxyOptions.getPassword() != null) {
        if (headers == null) {
          headers = MultiMap.caseInsensitiveMultiMap();
        }
        headers.add("Proxy-Authorization", "Basic " + Base64.getEncoder()
            .encodeToString((proxyOptions.getUsername() + ":" + proxyOptions.getPassword()).getBytes()));
      }
      req = new HttpClientRequestImpl(this, useSSL, method, SocketAddress.inetSocketAddress(proxyOptions.getPort(), proxyOptions.getHost()),
          host, port, relativeURI, vertx);
    } else {
      if (server == null) {
        server = SocketAddress.inetSocketAddress(port, host);
      }
      req = new HttpClientRequestImpl(this, useSSL, method, server, host, port, relativeURI, vertx);
    }
    if (headers != null) {
      req.headers().setAll(headers);
    }
    return req;
  }

  private synchronized void checkClosed() {
    if (closed) {
      throw new IllegalStateException("Client is closed");
    }
  }

  @Override
  protected void finalize() throws Throwable {
    // Make sure this gets cleaned up if there are no more references to it
    // so as not to leave connections and resources dangling until the system is shutdown
    // which could make the JVM run out of file handles.
    close();
    super.finalize();
  }
}
