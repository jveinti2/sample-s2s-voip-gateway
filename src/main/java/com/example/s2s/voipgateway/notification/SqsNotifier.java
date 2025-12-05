package com.example.s2s.voipgateway.notification;

import com.example.s2s.voipgateway.tracing.CallTracer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.util.HashMap;
import java.util.Map;

public class SqsNotifier {
    private static final Logger LOG = LoggerFactory.getLogger(SqsNotifier.class);
    private static final String QUEUE_NAME = "nova-sonic-emt";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final SqsClient sqsClient;
    private final String queueUrl;
    private final boolean enabled;

    public SqsNotifier() {
        String sqsRegion = System.getenv().getOrDefault("SQS_REGION", "us-east-1");
        String enabledStr = System.getenv().getOrDefault("SQS_ENABLED", "true");
        this.enabled = "true".equalsIgnoreCase(enabledStr);

        if (enabled) {
            try {
                this.sqsClient = SqsClient.builder()
                        .region(Region.of(sqsRegion))
                        .build();

                this.queueUrl = sqsClient.getQueueUrl(builder -> builder.queueName(QUEUE_NAME))
                        .queueUrl();

                LOG.info("SqsNotifier initialized. Queue URL: {}", queueUrl);
            } catch (Exception e) {
                LOG.error("Failed to initialize SqsNotifier", e);
                throw new RuntimeException("SQS initialization failed", e);
            }
        } else {
            this.sqsClient = null;
            this.queueUrl = null;
            LOG.info("SqsNotifier disabled via SQS_ENABLED=false");
        }
    }

    public void sendCallCompletedMessage(CallTracer tracer) {
        if (!enabled) {
            LOG.debug("SQS notifications disabled, skipping message");
            return;
        }

        if (tracer == null) {
            LOG.warn("Cannot send SQS message: tracer is null");
            return;
        }

        try {
            String conversationId = tracer.getVariable("uui_conversationid");
            if (conversationId == null || conversationId.isEmpty()) {
                LOG.warn("No conversationId found in tracer, skipping SQS message");
                return;
            }

            String clientId = tracer.getVariable("client_id");
            if (clientId == null || clientId.isEmpty()) {
                clientId = System.getenv().getOrDefault("CLIENT_ID", "unknown");
            }

            Map<String, String> payload = new HashMap<>();
            payload.put("conversationId", conversationId);
            payload.put("clientId", clientId);

            String messageBody = objectMapper.writeValueAsString(payload);

            SendMessageRequest sendRequest = SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(messageBody)
                    .build();

            SendMessageResponse response = sqsClient.sendMessage(sendRequest);

            LOG.info("Sent call completion to SQS. MessageId: {}, ConversationId: {}, ClientId: {}",
                    response.messageId(), conversationId, clientId);

        } catch (Exception e) {
            LOG.error("Failed to send message to SQS", e);
        }
    }

    public void close() {
        if (sqsClient != null) {
            sqsClient.close();
        }
    }
}
