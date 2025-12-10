package com.example.s2s.voipgateway.nova.context;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * Utility class for loading prompt fragments from classpath resources.
 *
 * This loader is optimized to read prompt text files at application startup
 * and cache them in memory. The loaded content is NOT sent to Nova Sonic until
 * explicitly requested via tool invocation.
 */
@Slf4j
public class PromptFragmentLoader {

    /**
     * Loads a text file from classpath resources.
     *
     * @param resourcePath Path relative to resources root (e.g., "/prompts/keralty/base-prompt.txt")
     * @return File content as String, or empty string if file not found
     */
    public static String loadFragment(String resourcePath) {
        try (InputStream is = PromptFragmentLoader.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                log.warn("Prompt fragment not found: {}", resourcePath);
                return "";
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String content = reader.lines().collect(Collectors.joining("\n"));
                log.debug("Loaded prompt fragment from {} ({} characters)", resourcePath, content.length());
                return content;
            }
        } catch (Exception e) {
            log.error("Error loading prompt fragment from {}: {}", resourcePath, e.getMessage());
            return "";
        }
    }

    /**
     * Checks if a resource exists in the classpath.
     *
     * @param resourcePath Path relative to resources root
     * @return true if resource exists, false otherwise
     */
    public static boolean exists(String resourcePath) {
        return PromptFragmentLoader.class.getResource(resourcePath) != null;
    }
}
