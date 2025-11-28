package com.example.s2s.voipgateway.tracing;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/**
 * Simple call tracer that captures only external variables from the provider.
 *
 * Output format: {type}:{value}
 * Example:
 *   sip_call_id:1234567890abcdef@sip-provider.com
 *   ani:573144779261
 *   dnis:576105101000
 *   client_id:keralty
 *
 * Design principles:
 * - Minimal: Only captures 4 external variables
 * - No timestamps: Provider transcript includes all timing information
 * - No events: Only initial variables for correlation with provider transcript
 */
@Slf4j
public class CallTracer implements AutoCloseable {

    private final String sipCallId;
    private final String ani;
    private final String dnis;
    private final String clientId;
    private final Path traceFile;

    /**
     * Creates a new call tracer and immediately writes external variables to file.
     *
     * @param sipCallId SIP Call-ID from provider (real interaction ID)
     * @param ani Automatic Number Identification (caller's phone number)
     * @param dnis Dialed Number Identification Service (called number)
     * @param clientId Client identifier (from CLIENT_ID env var)
     */
    public CallTracer(String sipCallId, String ani, String dnis, String clientId) {
        this.sipCallId = sipCallId;
        this.ani = ani;
        this.dnis = dnis;
        this.clientId = clientId;

        // Create trace file: logs/traces/{sip_call_id}.txt
        // Note: SIP Call-ID may contain special characters, sanitize for filename
        String sanitizedCallId = sanitizeFilename(sipCallId);
        this.traceFile = Paths.get("logs", "traces", sanitizedCallId + ".txt");

        // Write immediately
        writeExternalVariables();

        log.info("CallTracer initialized: sip_call_id={}, ani={}", sipCallId, ani);
    }

    /**
     * Sanitizes a string to be safe for use as a filename.
     * Replaces characters that are invalid in filenames with underscores.
     */
    private String sanitizeFilename(String input) {
        if (input == null) {
            return "unknown";
        }
        // Replace invalid filename characters with underscore
        return input.replaceAll("[<>:\"/\\\\|?*]", "_");
    }

    /**
     * Writes external variables to trace file.
     */
    private void writeExternalVariables() {
        try {
            // Create traces directory if it doesn't exist
            Files.createDirectories(traceFile.getParent());

            // Create content with 4 external variables
            List<String> lines = Arrays.asList(
                "sip_call_id:" + sipCallId,
                "ani:" + ani,
                "dnis:" + dnis,
                "client_id:" + clientId
            );

            // Write to file
            Files.write(traceFile, lines, StandardCharsets.UTF_8);

            log.info("Trace written: {} (4 external variables)", traceFile);

        } catch (IOException e) {
            log.error("Error writing trace for sip_call_id={}: {}", sipCallId, e.getMessage(), e);
        }
    }

    /**
     * Returns the SIP Call-ID for this trace.
     */
    public String getCallId() {
        return sipCallId;
    }

    @Override
    public void close() {
        // No action needed - file already written in constructor
        log.debug("CallTracer closed for sip_call_id={}", sipCallId);
    }
}
