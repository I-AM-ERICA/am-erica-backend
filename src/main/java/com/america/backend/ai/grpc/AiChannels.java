package com.america.backend.ai.grpc;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/** 비서별 gRPC 채널을 하나씩 유지합니다. 채널은 첫 호출 시 연결되며 끊기면 자동 재연결됩니다. */
@Component
public class AiChannels implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AiChannels.class);

    private final Map<AssistantType, ManagedChannel> channels = new EnumMap<>(AssistantType.class);

    public AiChannels(AiGrpcProperties properties) {
        InternalKeyClientInterceptor interceptor = new InternalKeyClientInterceptor(properties.internalKey());
        for (AssistantType type : AssistantType.values()) {
            AiGrpcProperties.Endpoint endpoint = properties.endpoint(type);
            ChannelCredentials credentials = endpoint.tls()
                    ? TlsChannelCredentials.create()
                    : InsecureChannelCredentials.create();
            channels.put(type, Grpc.newChannelBuilderForAddress(endpoint.host(), endpoint.port(), credentials)
                    .intercept(interceptor)
                    .build());
            log.info("{} AI 비서 gRPC 채널: {}:{} (tls={})", type.label(), endpoint.host(), endpoint.port(), endpoint.tls());
        }
    }

    public ManagedChannel get(AssistantType type) {
        return channels.get(type);
    }

    @Override
    public void destroy() throws InterruptedException {
        channels.values().forEach(ManagedChannel::shutdown);
        for (ManagedChannel channel : channels.values()) {
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        }
    }
}
