package com.example.s2s.voipgateway.tracing;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Simple call tracer that records events in a numbered line format.
 *
 * Output format: {line_number}:{type}:{value}
 * Example:
 *   1:call_id:abc123
 *   2:ani:3144779261
 *   3:estado:citas
 *   4:proceso:Centro Médico Keralty Norte
 *
 * Design principles:
 * - Simple: No complex JSON structures
 * - Generic: Works for any client without code changes
 * - Non-invasive: AI does not participate in tracing
 * - Observable: Only captures existing data flows
 */
@Slf4j
public class CallTracer {

    private final String callId;
    private final String ani;
    private final String dnis;
    private final String clientId;
    private final List<String> entries = new ArrayList<>();
    private int lineNumber = 1;
    private final Instant startTime;

    /**
     * Creates a new call tracer and records initial metadata.
     *
     * @param callId Unique identifier for this call
     * @param ani Automatic Number Identification (caller's phone number)
     * @param dnis Dialed Number Identification Service (called number)
     * @param clientId Client identifier (from CLIENT_ID env var)
     */
    public CallTracer(String callId, String ani, String dnis, String clientId) {
        this.callId = callId;
        this.ani = ani;
        this.dnis = dnis;
        this.clientId = clientId;
        this.startTime = Instant.now();

        // Record initial metadata
        record("call_id", callId);
        record("ani", ani);
        record("dnis", dnis);
        record("client_id", clientId);
        record("start_time", startTime.toString());

        log.info("CallTracer initialized for call_id={}, ani={}", callId, ani);
    }

    /**
     * Records a single trace entry.
     *
     * @param type The type of entry (e.g., "estado", "proceso", "tool", "error")
     * @param value The value to record
     */
    public synchronized void record(String type, String value) {
        if (type == null || value == null) {
            log.warn("Skipping null record: type={}, value={}", type, value);
            return;
        }

        // Sanitize value: remove line breaks that could break format
        String sanitizedValue = value.replaceAll("[\r\n]+", " ");

        String entry = lineNumber++ + ":" + type + ":" + sanitizedValue;
        entries.add(entry);

        log.debug("Trace recorded: {}", entry);
    }

    /**
     * Flushes the trace to disk.
     *
     * Writes to: logs/traces/{call_id}.txt
     *
     * This method is idempotent and can be called multiple times.
     * Each call will overwrite the previous file with updated content.
     */
    public synchronized void flush() {
        try {
            // Create traces directory if it doesn't exist
            Path dir = Paths.get("logs/traces");
            Files.createDirectories(dir);

            // Append end metadata
            Instant endTime = Instant.now();
            record("end_time", endTime.toString());
            record("duration_seconds",
                   String.valueOf(Duration.between(startTime, endTime).getSeconds()));

            // Write to file
            Path file = dir.resolve(callId + ".txt");
            Files.write(file, entries, StandardCharsets.UTF_8);

            log.info("Trace written successfully: {} ({} entries)", file, entries.size());

        } catch (IOException e) {
            log.error("Error writing trace for call_id={}: {}", callId, e.getMessage(), e);
        }
    }

    /**
     * Returns the call ID for this trace.
     */
    public String getCallId() {
        return callId;
    }

    /**
     * Returns the number of entries recorded so far.
     */
    public int getEntryCount() {
        return entries.size();
    }
}
