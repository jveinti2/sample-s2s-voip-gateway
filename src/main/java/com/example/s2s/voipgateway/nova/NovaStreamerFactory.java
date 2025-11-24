package com.example.s2s.voipgateway.nova;

import com.example.s2s.voipgateway.constants.MediaTypes;
import com.example.s2s.voipgateway.constants.SonicAudioConfig;
import com.example.s2s.voipgateway.constants.SonicAudioTypes;
import com.example.s2s.voipgateway.nova.event.*;
import com.example.s2s.voipgateway.nova.context.HybridEventHandler;
import com.example.s2s.voipgateway.NovaMediaConfig;
import com.example.s2s.voipgateway.tracing.CallTracer;
import com.example.s2s.voipgateway.NovaSonicAudioInput;
import com.example.s2s.voipgateway.NovaSonicAudioOutput;
import com.example.s2s.voipgateway.nova.observer.InteractObserver;
import org.mjsip.media.AudioStreamer;
import org.mjsip.media.FlowSpec;
import org.mjsip.media.MediaStreamer;
import org.mjsip.media.StreamerOptions;
import org.mjsip.media.rx.AudioReceiver;
import org.mjsip.media.tx.AudioTransmitter;
import org.mjsip.ua.streamer.StreamerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.http.Protocol;
import software.amazon.awssdk.http.ProtocolNegotiation;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Executor;

/**
 * StreamerFactory implementation for Amazon Nova Sonic.
 */
public class NovaStreamerFactory implements StreamerFactory {
    private static final Logger log = LoggerFactory.getLogger(NovaStreamerFactory.class);
    private static final String ROLE_SYSTEM = "SYSTEM";
    private final NovaMediaConfig mediaConfig;
    private CallTracer tracer; // null-safe: can be null if not set

    public NovaStreamerFactory(NovaMediaConfig mediaConfig) {
        this.mediaConfig = mediaConfig;
        this.tracer = null;
    }

    /**
     * Creates a copy of this factory with the specified tracer attached.
     * This allows the factory to be reused while providing call-specific tracers.
     *
     * @param tracer The call tracer for this specific call
     * @return A new StreamerFactory instance with the tracer attached
     */
    public StreamerFactory withTracer(CallTracer tracer) {
        NovaStreamerFactory copy = new NovaStreamerFactory(this.mediaConfig);
        copy.tracer = tracer;
        return copy;
    }

    @Override
    public MediaStreamer createMediaStreamer(Executor executor, FlowSpec flowSpec) {
        log.info("Creating Nova streamer ...");
        NettyNioAsyncHttpClient.Builder nettyBuilder = NettyNioAsyncHttpClient.builder()
                .readTimeout(Duration.of(180, ChronoUnit.SECONDS))
                .maxConcurrency(20)
                .protocol(Protocol.HTTP2)
                .protocolNegotiation(ProtocolNegotiation.ALPN);

        BedrockRuntimeAsyncClient client = BedrockRuntimeAsyncClient.builder()
                .region(Region.US_EAST_1)
                .httpClientBuilder(nettyBuilder)
                .build();

        String promptName = UUID.randomUUID().toString();

        NovaS2SBedrockInteractClient novaClient = new NovaS2SBedrockInteractClient(client, "amazon.nova-sonic-v1:0");
        NovaS2SEventHandler eventHandler = new HybridEventHandler(tracer);

        log.info("Using system prompt: {}", mediaConfig.getNovaPrompt());

        InteractObserver<NovaSonicEvent> inputObserver = novaClient.interactMultimodal(
                createSessionStartEvent(),
                createPromptStartEvent(promptName, eventHandler),
                createSystemPrompt(promptName, mediaConfig.getNovaPrompt()),
                eventHandler);

        eventHandler.setOutbound(inputObserver);
        AudioTransmitter tx = new NovaSonicAudioInput(eventHandler);
        AudioReceiver rx = new NovaSonicAudioOutput(inputObserver, promptName);

        StreamerOptions options = StreamerOptions.builder()
                .setRandomEarlyDrop(mediaConfig.getRandomEarlyDropRate())
                .setSymmetricRtp(mediaConfig.isSymmetricRtp())
                .build();

        log.debug("Created AudioStreamer");
        return new AudioStreamer(executor, flowSpec, tx, rx, options);
    }

    /**
     * Creates the PromptStart event.
     * @param promptName The prompt name for the session.
     * @param eventHandler The event handler for the session.
     * @return The PromptStartEvent
     */
    private PromptStartEvent createPromptStartEvent(String promptName, NovaS2SEventHandler eventHandler) {
        return new PromptStartEvent(PromptStartEvent.PromptStart.builder()
                .promptName(promptName)
                .textOutputConfiguration(MediaConfiguration.builder().mediaType(MediaTypes.TEXT_PLAIN).build())
                .audioOutputConfiguration(PromptStartEvent.AudioOutputConfiguration.builder()
                        .mediaType(MediaTypes.AUDIO_LPCM)
                        .sampleRateHertz(SonicAudioConfig.SAMPLE_RATE)
                        .sampleSizeBits(SonicAudioConfig.SAMPLE_SIZE)
                        .channelCount(SonicAudioConfig.CHANNEL_COUNT)
                        .voiceId(mediaConfig.getNovaVoiceId())
                        .encoding(SonicAudioConfig.ENCODING_BASE64)
                        .audioType(SonicAudioTypes.SPEECH)
                        .build())
                .toolUseOutputConfiguration(MediaConfiguration.builder().mediaType(MediaTypes.APPLICATION_JSON).build())
                .toolConfiguration(eventHandler.getToolConfiguration())
                .build());
    }

    /**
     * Creates the SessionStart event.
     * @return The SessionStartEvent
     */
    private SessionStartEvent createSessionStartEvent() {
        return new SessionStartEvent(mediaConfig.getNovaMaxTokens(), mediaConfig.getNovaTopP(), mediaConfig.getNovaTemperature());
    }

    /**
     * Creates the system prompt.
     * @param promptName The prompt name for the session.
     * @param systemPrompt The system prompt.
     * @return The system prompt as a TextInputEvent.
     */
    private static TextInputEvent createSystemPrompt(String promptName, String systemPrompt) {
        return new TextInputEvent(TextInputEvent.TextInput.builder()
                .promptName(promptName)
                .contentName(UUID.randomUUID().toString())
                .content(systemPrompt)
                .role(ROLE_SYSTEM)
                .build());
    }
}
