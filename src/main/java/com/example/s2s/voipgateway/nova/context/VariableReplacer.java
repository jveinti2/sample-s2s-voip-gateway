package com.example.s2s.voipgateway.nova.context;

import com.example.s2s.voipgateway.tracing.CallTracer;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Utility class for replacing variable placeholders in prompts with actual values from CallTracer.
 * Supports multiple placeholder formats: ${variable} and [variable].
 */
@Slf4j
public class VariableReplacer {

    /**
     * Replace variable placeholders with actual values from CallTracer.
     *
     * Supported formats:
     * - ${variable_name} - Standard format
     * - [variable_name]  - Alternate format
     *
     * Variable resolution strategies:
     * 1. Direct match: ${sip_call_id} or [sip_call_id] → value from key "sip_call_id"
     * 2. UUI match: ${monto_deuda} or [monto_deuda] → value from key "uui_monto_deuda"
     *
     * @param content Content with variable placeholders
     * @param tracer  CallTracer containing variable values
     * @return Content with placeholders replaced by values
     */
    public static String replaceVariables(String content, CallTracer tracer) {
        if (tracer == null) {
            log.warn("CallTracer is null - cannot replace variables");
            return content;
        }

        Map<String, String> allVariables = tracer.getAllVariables();

        if (allVariables.isEmpty()) {
            log.warn("No variables available in CallTracer for replacement");
            return content;
        }

        log.info("Found {} variables in CallTracer for replacement", allVariables.size());

        // DEBUG: Log variables if debug enabled
        if (log.isDebugEnabled()) {
            log.debug("=== VARIABLES BEFORE REPLACEMENT ===");
            allVariables.forEach((k, v) -> log.debug("  {} = '{}'", k, v));
            log.debug("=== CONTENT BEFORE REPLACEMENT (first 500 chars) ===");
            log.debug("{}", content.substring(0, Math.min(500, content.length())));
        }

        int replacementCount = 0;

        for (Map.Entry<String, String> entry : allVariables.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (value == null || value.isEmpty()) {
                continue;
            }

            // Strategy 1: Direct replacement with ${key}
            String dollarPlaceholder = "${" + key + "}";
            if (content.contains(dollarPlaceholder)) {
                content = content.replace(dollarPlaceholder, value);
                log.debug("Replaced {} with '{}'", dollarPlaceholder, value);
                replacementCount++;
            }

            // Strategy 2: Direct replacement with [key]
            String bracketPlaceholder = "[" + key + "]";
            if (content.contains(bracketPlaceholder)) {
                content = content.replace(bracketPlaceholder, value);
                log.debug("Replaced {} with '{}'", bracketPlaceholder, value);
                replacementCount++;
            }

            // Strategy 3 & 4: If key starts with "uui_", try without prefix
            if (key.startsWith("uui_")) {
                String keyWithoutPrefix = key.substring(4);

                String dollarPrefixless = "${" + keyWithoutPrefix + "}";
                if (content.contains(dollarPrefixless)) {
                    content = content.replace(dollarPrefixless, value);
                    log.debug("Replaced {} with '{}' (from key: {})", dollarPrefixless, value, key);
                    replacementCount++;
                }

                String bracketPrefixless = "[" + keyWithoutPrefix + "]";
                if (content.contains(bracketPrefixless)) {
                    content = content.replace(bracketPrefixless, value);
                    log.debug("Replaced {} with '{}' (from key: {})", bracketPrefixless, value, key);
                    replacementCount++;
                }
            }
        }

        log.info("Variable replacement complete: {} replacements made", replacementCount);

        // DEBUG: Log content after replacement
        if (log.isDebugEnabled()) {
            log.debug("=== CONTENT AFTER REPLACEMENT (first 500 chars) ===");
            log.debug("{}", content.substring(0, Math.min(500, content.length())));
            log.debug("=== FULL CONTENT LENGTH: {} chars ===", content.length());
        }

        return content;
    }
}
