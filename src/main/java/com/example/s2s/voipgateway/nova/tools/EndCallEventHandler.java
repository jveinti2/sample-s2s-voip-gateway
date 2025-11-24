package com.example.s2s.voipgateway.nova.tools;

import com.example.s2s.voipgateway.nova.AbstractNovaS2SEventHandler;
import com.example.s2s.voipgateway.nova.event.PromptStartEvent;
import com.example.s2s.voipgateway.tracing.CallTracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;

/**
 * Event handler for the endCall tool.
 *
 * This tool allows Nova Sonic to explicitly signal that the conversation should end.
 * When invoked, it:
 * 1. Records the termination request in the trace
 * 2. Marks the call as complete
 * 3. Returns success to AI so it can provide final farewell
 *
 * The actual SIP hangup is handled by the session lifecycle (onComplete/onError).
 */
public class EndCallEventHandler extends AbstractNovaS2SEventHandler {
    private static final Logger log = LoggerFactory.getLogger(EndCallEventHandler.class);
    private volatile boolean callEndRequested = false;

    public EndCallEventHandler() {
        super();
    }

    public EndCallEventHandler(CallTracer tracer) {
        super(tracer);
    }

    @Override
    public void handleToolInvocation(String toolUseId, String toolName, String content, Map<String, Object> output) {
        if (!"endCall".equals(toolName)) {
            log.warn("Unknown tool invoked: {}", toolName);
            output.put("error", "Unknown tool: " + toolName);
            return;
        }

        log.info("endCall tool invoked - call termination requested (toolUseId: {})", toolUseId);

        // Mark call as ending
        callEndRequested = true;

        // Record in trace
        if (tracer != null) {
            tracer.record("call_action", "end_requested");
        }

        // Return success to AI
        output.put("success", true);
        output.put("message", "Call termination initiated. Provide brief farewell.");
        output.put("instruction", "Say goodbye in ONE sentence, then conversation will end.");

        log.info("endCall tool completed successfully");
    }

    @Override
    public PromptStartEvent.ToolConfiguration getToolConfiguration() {
        return PromptStartEvent.ToolConfiguration.builder()
            .tools(Collections.singletonList(
                PromptStartEvent.Tool.builder()
                    .toolSpec(PromptStartEvent.ToolSpec.builder()
                        .name("endCall")
                        .description("End the current call when conversation naturally concludes. " +
                                   "Use this tool when: " +
                                   "(1) User says goodbye/thanks and confirms no more questions, " +
                                   "(2) Task is complete and user is satisfied, " +
                                   "(3) After providing final information. " +
                                   "CRITICAL: Call this BEFORE your final farewell message, not after. " +
                                   "After calling this tool, say goodbye in ONE sentence only.")
                        .inputSchema(ToolSpecs.DEFAULT_TOOL_SPEC)
                        .build())
                    .build()
            ))
            .build();
    }

    /**
     * Returns true if endCall was invoked for this call.
     */
    public boolean isCallEndRequested() {
        return callEndRequested;
    }
}
