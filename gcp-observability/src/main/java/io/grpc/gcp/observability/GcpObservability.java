/*
 * Copyright 2022 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.gcp.observability;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.ClientInterceptor;
import io.grpc.ExperimentalApi;
import io.grpc.InternalGlobalInterceptors;
import io.grpc.ManagedChannelProvider.ProviderNotFoundException;
import io.grpc.ServerInterceptor;
import io.grpc.ServerStreamTracer;
import io.grpc.census.InternalCensusStatsAccessor;
import io.grpc.census.InternalCensusTracingAccessor;
import io.grpc.gcp.observability.interceptors.ConfigFilterHelper;
import io.grpc.gcp.observability.interceptors.InternalLoggingChannelInterceptor;
import io.grpc.gcp.observability.interceptors.InternalLoggingServerInterceptor;
import io.grpc.gcp.observability.interceptors.LogHelper;
import io.grpc.gcp.observability.logging.GcpLogSink;
import io.grpc.gcp.observability.logging.Sink;
import io.grpc.internal.TimeProvider;
import io.opencensus.contrib.grpc.metrics.RpcViews;
import io.opencensus.exporter.stats.stackdriver.StackdriverStatsConfiguration;
import io.opencensus.exporter.stats.stackdriver.StackdriverStatsExporter;
import io.opencensus.exporter.trace.stackdriver.StackdriverTraceConfiguration;
import io.opencensus.exporter.trace.stackdriver.StackdriverTraceExporter;
import io.opencensus.trace.Tracing;
import io.opencensus.trace.config.TraceConfig;
import java.io.IOException;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/** The main class for gRPC Google Cloud Platform Observability features. */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/8869")
public final class GcpObservability implements AutoCloseable {
  private static final Logger logger = Logger.getLogger(GcpObservability.class.getName());
  private static GcpObservability instance = null;
  private final Sink sink;
  private final ObservabilityConfig config;
  private final ArrayList<ClientInterceptor> clientInterceptors = new ArrayList<>();
  private final ArrayList<ServerInterceptor> serverInterceptors = new ArrayList<>();
  private final ArrayList<ServerStreamTracer.Factory> tracerFactories = new ArrayList<>();
  private boolean metricsEnabled;
  private boolean tracesEnabled;

  /**
   * Initialize grpc-observability.
   *
   * @throws ProviderNotFoundException if no underlying channel/server provider is available.
   */
  public static synchronized GcpObservability grpcInit() throws IOException {
    if (instance == null) {
      GlobalLoggingTags globalLoggingTags = new GlobalLoggingTags();
      ObservabilityConfigImpl observabilityConfig = ObservabilityConfigImpl.getInstance();
      Sink sink = new GcpLogSink(observabilityConfig.getDestinationProjectId(),
          globalLoggingTags.getLocationTags(), globalLoggingTags.getCustomTags(),
          observabilityConfig.getFlushMessageCount());
      // TODO(dnvindhya): Cleanup code for LoggingChannelProvider and LoggingServerProvider
      // once ChannelBuilder and ServerBuilder are used
      LogHelper helper = new LogHelper(sink, TimeProvider.SYSTEM_TIME_PROVIDER);
      ConfigFilterHelper configFilterHelper = ConfigFilterHelper.factory(observabilityConfig);
      instance = grpcInit(sink, observabilityConfig,
          new InternalLoggingChannelInterceptor.FactoryImpl(helper, configFilterHelper),
          new InternalLoggingServerInterceptor.FactoryImpl(helper, configFilterHelper));
      instance.registerStackDriverExporter(observabilityConfig.getDestinationProjectId());
    }
    return instance;
  }

  @VisibleForTesting
  static GcpObservability grpcInit(
      Sink sink,
      ObservabilityConfig config,
      InternalLoggingChannelInterceptor.Factory channelInterceptorFactory,
      InternalLoggingServerInterceptor.Factory serverInterceptorFactory)
      throws IOException {
    if (instance == null) {
      instance =
          new GcpObservability(sink, config, channelInterceptorFactory, serverInterceptorFactory);
      LogHelper logHelper = new LogHelper(sink, TimeProvider.SYSTEM_TIME_PROVIDER);
      ConfigFilterHelper logFilterHelper = ConfigFilterHelper.factory(config);
      instance.setProducer(
          new InternalLoggingChannelInterceptor.FactoryImpl(logHelper, logFilterHelper),
          new InternalLoggingServerInterceptor.FactoryImpl(logHelper, logFilterHelper));
    }
    return instance;
  }

  /** Un-initialize/shutdown grpc-observability. */
  @Override
  public void close() {
    synchronized (GcpObservability.class) {
      if (instance == null) {
        throw new IllegalStateException("GcpObservability already closed!");
      }
      unRegisterStackDriverExporter();
      LoggingChannelProvider.shutdown();
      LoggingServerProvider.shutdown();
      sink.close();
      instance = null;
    }
  }

  private void setProducer(
      InternalLoggingChannelInterceptor.Factory channelInterceptorFactory,
      InternalLoggingServerInterceptor.Factory serverInterceptorFactory) {
    if (config.isEnableCloudLogging()) {
      clientInterceptors.add(channelInterceptorFactory.create());
      serverInterceptors.add(serverInterceptorFactory.create());
    }
    if (config.isEnableCloudMonitoring()) {
      clientInterceptors.add(
          InternalCensusStatsAccessor.getClientInterceptor(true, true, true, true));
      tracerFactories.add(
          InternalCensusStatsAccessor.getServerStreamTracerFactory(true, true, true));
    }
    if (config.isEnableCloudTracing()) {
      clientInterceptors.add(InternalCensusTracingAccessor.getClientInterceptor());
      tracerFactories.add(InternalCensusTracingAccessor.getServerStreamTracerFactory());
    }

    InternalGlobalInterceptors.setInterceptorsTracers(
        clientInterceptors, serverInterceptors, tracerFactories);
  }

  private void registerStackDriverExporter(String projectId) throws IOException {
    if (config.isEnableCloudMonitoring()) {
      RpcViews.registerAllGrpcViews();
      StackdriverStatsConfiguration.Builder statsConfigurationBuilder =
          StackdriverStatsConfiguration.builder();
      if (projectId != null) {
        statsConfigurationBuilder.setProjectId(projectId);
      }
      StackdriverStatsExporter.createAndRegister(statsConfigurationBuilder.build());
      metricsEnabled = true;
    }

    if (config.isEnableCloudTracing()) {
      TraceConfig traceConfig = Tracing.getTraceConfig();
      traceConfig.updateActiveTraceParams(
          traceConfig.getActiveTraceParams().toBuilder().setSampler(config.getSampler()).build());
      StackdriverTraceConfiguration.Builder traceConfigurationBuilder =
          StackdriverTraceConfiguration.builder();
      if (projectId != null) {
        traceConfigurationBuilder.setProjectId(projectId);
      }
      StackdriverTraceExporter.createAndRegister(traceConfigurationBuilder.build());
      tracesEnabled = true;
    }
  }

  private void unRegisterStackDriverExporter() {
    if (metricsEnabled) {
      try {
        StackdriverStatsExporter.unregister();
      } catch (IllegalStateException e) {
        logger.log(
            Level.SEVERE, "Failed to unregister Stackdriver stats exporter, " + e.getMessage());
      }
      metricsEnabled = false;
    }

    if (tracesEnabled) {
      try {
        StackdriverTraceExporter.unregister();
      } catch (IllegalStateException e) {
        logger.log(
            Level.SEVERE, "Failed to unregister Stackdriver trace exporter, " + e.getMessage());
      }
      tracesEnabled = false;
    }
  }

  private GcpObservability(
      Sink sink,
      ObservabilityConfig config,
      InternalLoggingChannelInterceptor.Factory channelInterceptorFactory,
      InternalLoggingServerInterceptor.Factory serverInterceptorFactory) {
    this.sink = checkNotNull(sink);
    this.config = checkNotNull(config);

    LoggingChannelProvider.init(checkNotNull(channelInterceptorFactory));
    LoggingServerProvider.init(checkNotNull(serverInterceptorFactory));
  }
}
