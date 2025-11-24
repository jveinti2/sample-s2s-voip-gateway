package com.example.s2s.voipgateway.nova.context;

import com.example.s2s.voipgateway.nova.AbstractNovaS2SEventHandler;
import com.example.s2s.voipgateway.nova.event.PromptStartEvent;
import com.example.s2s.voipgateway.nova.observer.InteractObserver;
import com.example.s2s.voipgateway.nova.event.NovaSonicEvent;
import com.example.s2s.voipgateway.tracing.CallTracer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.net.URL;
import java.util.*;

/**
 * Dynamic context loader that auto-discovers available contexts based on CLIENT_ID.
 *
 * This handler scans the prompts/{CLIENT_ID}/ directory at startup and registers
 * all context-*.txt files as available contexts. It exposes a single generic tool
 * "loadContext" that accepts a "context" parameter to load the requested fragment.
 *
 * Architecture:
 * - Fragments are loaded into memory at class initialization (static block)
 * - Content is NOT sent to Nova Sonic until the tool is explicitly invoked
 * - Supports multi-client deployment via CLIENT_ID environment variable
 *
 * Directory convention:
 * prompts/
 * ├── {CLIENT_ID}/
 * │   ├── base-prompt.txt         (loaded separately by NovaStreamerFactory)
 * │   ├── context-citas.txt       (loaded on-demand via tool)
 * │   ├── context-pqrs.txt        (loaded on-demand via tool)
 * │   └── context-*.txt           (any additional contexts)
 * └── default/
 *     └── base-prompt.txt         (fallback if CLIENT_ID not found)
 */
@Slf4j
public class DynamicContextLoaderEventHandler extends AbstractNovaS2SEventHandler {

    private static final String CLIENT_ID = System.getenv().getOrDefault("CLIENT_ID", "keralty");
    private static final Map<String, String> CONTEXT_FRAGMENTS = new HashMap<>();
    private static final List<String> AVAILABLE_CONTEXTS = new ArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    static {
        loadClientContexts();
    }

    public DynamicContextLoaderEventHandler() {
        super();
    }

    public DynamicContextLoaderEventHandler(CallTracer tracer) {
        super(tracer);
    }

    public DynamicContextLoaderEventHandler(InteractObserver<NovaSonicEvent> outbound) {
        super(outbound);
    }

    /**
     * Scans prompts/{CLIENT_ID}/ directory and loads all context-*.txt files into memory.
     *
     * This runs once at class initialization. The loaded content stays in RAM but
     * is NOT sent to Nova Sonic until a tool invocation requests it.
     */
    private static void loadClientContexts() {
        String basePath = "prompts/" + CLIENT_ID;
        log.info("Loading contexts for client: {} from path: {}", CLIENT_ID, basePath);

        try {
            ClassLoader classLoader = DynamicContextLoaderEventHandler.class.getClassLoader();
            URL resource = classLoader.getResource(basePath);

            if (resource == null) {
                log.warn("Client directory not found: {}. No contexts will be available.", basePath);
                return;
            }

            File dir = new File(resource.toURI());
            File[] contextFiles = dir.listFiles((d, name) ->
                name.startsWith("context-") && name.endsWith(".txt")
            );

            if (contextFiles == null || contextFiles.length == 0) {
                log.warn("No context files found in {}", basePath);
                return;
            }

            for (File file : contextFiles) {
                // Extract context name: "context-citas.txt" → "citas"
                String contextName = file.getName()
                    .replace("context-", "")
                    .replace(".txt", "");

                // Load content from classpath
                String resourcePath = "/" + basePath + "/" + file.getName();
                String content = PromptFragmentLoader.loadFragment(resourcePath);

                if (!content.isEmpty()) {
                    CONTEXT_FRAGMENTS.put(contextName, content);
                    AVAILABLE_CONTEXTS.add(contextName);
                    log.info("✓ Loaded context '{}' for client '{}' ({} characters)",
                        contextName, CLIENT_ID, content.length());
                } else {
                    log.warn("✗ Failed to load context '{}' - file was empty or unreadable", contextName);
                }
            }

            log.info("Context loading complete. Available contexts: {}", AVAILABLE_CONTEXTS);

        } catch (Exception e) {
            log.error("Error loading client contexts for {}: {}", CLIENT_ID, e.getMessage(), e);
        }
    }

    @Override
    public void handleToolInvocation(String toolUseId, String toolName, String content,
                                       Map<String, Object> output) {
        if (!"loadContext".equals(toolName)) {
            log.warn("Unknown tool invoked: {}", toolName);
            output.put("error", "Unknown tool: " + toolName);
            return;
        }

        try {
            // Parse input JSON to extract context name
            Map<String, String> input = objectMapper.readValue(content,
                new TypeReference<Map<String, String>>() {});

            String contextName = input.get("context");

            if (contextName == null || contextName.isEmpty()) {
                output.put("contextLoaded", false);
                output.put("error", "Missing 'context' parameter");
                output.put("availableContexts", AVAILABLE_CONTEXTS);
                log.warn("loadContext called without 'context' parameter");
                return;
            }

            if (CONTEXT_FRAGMENTS.containsKey(contextName)) {
                String instructions = CONTEXT_FRAGMENTS.get(contextName);
                output.put("contextLoaded", true);
                output.put("contextType", contextName);
                output.put("instructions", instructions);
                output.put("message", "Contexto cargado exitosamente: " + contextName +
                                     ". Procede con el flujo usando estas instrucciones.");
                log.info("Context '{}' loaded successfully for tool use {}", contextName, toolUseId);
            } else {
                output.put("contextLoaded", false);
                output.put("error", "Context not found: " + contextName);
                output.put("availableContexts", AVAILABLE_CONTEXTS);
                log.warn("Requested context '{}' not found. Available: {}", contextName, AVAILABLE_CONTEXTS);
            }

        } catch (Exception e) {
            output.put("contextLoaded", false);
            output.put("error", "Error parsing context request: " + e.getMessage());
            log.error("Error handling loadContext invocation: {}", e.getMessage(), e);
        }
    }

    @Override
    public PromptStartEvent.ToolConfiguration getToolConfiguration() {
        return PromptStartEvent.ToolConfiguration.builder()
            .tools(Collections.singletonList(
                PromptStartEvent.Tool.builder()
                    .toolSpec(PromptStartEvent.ToolSpec.builder()
                        .name("loadContext")
                        .description("Load detailed conversational instructions for a specific state or flow. " +
                                   "Available contexts: " + String.join(", ", AVAILABLE_CONTEXTS) +
                                   ". You MUST call this tool when transitioning to a new conversational state " +
                                   "to receive the detailed instructions for that state.")
                        .inputSchema(createContextSchema())
                        .build())
                    .build()
            ))
            .build();
    }

    /**
     * Creates JSON Schema for the loadContext tool input.
     * Restricts the "context" parameter to available contexts via enum.
     */
    private Map<String, String> createContextSchema() {
        try {
            Map<String, Object> contextProperty = new HashMap<>();
            contextProperty.put("type", "string");
            contextProperty.put("description", "Name of the context to load. Must be one of: " +
                                              String.join(", ", AVAILABLE_CONTEXTS));

            // Only add enum if we have contexts (avoids empty enum which is invalid)
            if (!AVAILABLE_CONTEXTS.isEmpty()) {
                contextProperty.put("enum", AVAILABLE_CONTEXTS);
            }

            PromptStartEvent.ToolSchema schema = PromptStartEvent.ToolSchema.builder()
                .type("object")
                .properties(Collections.singletonMap("context", contextProperty))
                .required(Collections.singletonList("context"))
                .build();

            return Collections.singletonMap("json", objectMapper.writeValueAsString(schema));

        } catch (Exception e) {
            log.error("Error creating context schema: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create tool schema", e);
        }
    }

    /**
     * Returns the list of available contexts for this client.
     * Useful for debugging and testing.
     */
    public static List<String> getAvailableContexts() {
        return Collections.unmodifiableList(AVAILABLE_CONTEXTS);
    }

    /**
     * Returns the configured CLIENT_ID.
     * Useful for debugging and testing.
     */
    public static String getClientId() {
        return CLIENT_ID;
    }
}
