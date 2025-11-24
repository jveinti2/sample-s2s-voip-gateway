package com.example.s2s.voipgateway.nova.context;

import com.example.s2s.voipgateway.nova.AbstractNovaS2SEventHandler;
import com.example.s2s.voipgateway.nova.event.NovaSonicEvent;
import com.example.s2s.voipgateway.nova.event.PromptStartEvent;
import com.example.s2s.voipgateway.nova.observer.InteractObserver;
import com.example.s2s.voipgateway.nova.tools.DateTimeNovaS2SEventHandler;
import com.example.s2s.voipgateway.tracing.CallTracer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hybrid event handler that combines:
 * 1. Dynamic context loading (on-demand prompt augmentation)
 * 2. Functional tools (e.g., DateTime utilities)
 *
 * This handler delegates tool invocations to the appropriate sub-handler based on tool name:
 * - "loadContext" → DynamicContextLoaderEventHandler
 * - "getDateTool", "getTimeTool" → DateTimeNovaS2SEventHandler
 *
 * Architecture:
 * - Merges tool configurations from multiple handlers
 * - Routes tool invocations to correct handler
 * - Maintains single unified interface for NovaStreamerFactory
 */
@Slf4j
public class HybridEventHandler extends AbstractNovaS2SEventHandler {

    private final DynamicContextLoaderEventHandler contextLoader;
    private final DateTimeNovaS2SEventHandler dateTimeHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HybridEventHandler() {
        super();
        this.contextLoader = new DynamicContextLoaderEventHandler();
        this.dateTimeHandler = new DateTimeNovaS2SEventHandler();
        log.info("HybridEventHandler initialized with context loader (CLIENT_ID: {}) and datetime tools",
            DynamicContextLoaderEventHandler.getClientId());
    }

    public HybridEventHandler(CallTracer tracer) {
        super(tracer);
        this.contextLoader = new DynamicContextLoaderEventHandler(tracer);
        this.dateTimeHandler = new DateTimeNovaS2SEventHandler(tracer);
        log.info("HybridEventHandler initialized with tracer for call_id={}",
                 tracer != null ? tracer.getCallId() : "null");
    }

    public HybridEventHandler(InteractObserver<NovaSonicEvent> outbound) {
        super(outbound);
        this.contextLoader = new DynamicContextLoaderEventHandler(outbound);
        this.dateTimeHandler = new DateTimeNovaS2SEventHandler();
        log.info("HybridEventHandler initialized with outbound observer");
    }

    @Override
    public void handleToolInvocation(String toolUseId, String toolName, String content,
                                       Map<String, Object> output) {
        if (toolName == null) {
            log.warn("Received null toolName for invocation {}", toolUseId);
            output.put("error", "Tool name was null");
            return;
        }

        log.debug("Routing tool invocation: {} (id: {})", toolName, toolUseId);

        // RECORD TOOL INVOCATION (before delegation)
        if (tracer != null) {
            tracer.record("tool", toolName);
        }

        // Parse input for generic extraction
        Map<String, Object> input = parseInput(content);

        // Route to appropriate handler based on tool name
        switch (toolName) {
            case "loadContext":
                log.info("Routing to DynamicContextLoaderEventHandler");
                contextLoader.handleToolInvocation(toolUseId, toolName, content, output);
                break;

            case "getDateTool":
            case "getTimeTool":
                log.info("Routing to DateTimeNovaS2SEventHandler");
                dateTimeHandler.handleToolInvocation(toolUseId, toolName, content, output);
                break;

            default:
                log.warn("Unknown tool invoked: {}. No handler available.", toolName);
                output.put("error", "Unknown tool: " + toolName);
                output.put("availableTools", List.of("loadContext", "getDateTool", "getTimeTool"));
        }

        // GENERIC EXTRACTION (after delegation, output is populated)
        if (tracer != null) {
            extractGeneric(toolName, input, output);
        }
    }

    /**
     * Parses JSON input content safely.
     * @param content JSON string
     * @return Map representation, or empty map if parsing fails
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseInput(String content) {
        if (content == null || content.isEmpty()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(content, Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse tool input JSON: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Generic extraction of trace data from tool invocations.
     * NO hardcoded client-specific logic (like "citas", "pqrs", "imagenes").
     * Uses conventions and configurable patterns.
     *
     * @param toolName Tool that was invoked
     * @param input Tool input parameters
     * @param output Tool output/result
     */
    private void extractGeneric(String toolName, Map<String, Object> input, Map<String, Object> output) {
        // RULE 1: loadContext tool → estado
        // Generic: Extract whatever value is in the "context" parameter
        if ("loadContext".equals(toolName) && input.containsKey("context")) {
            String contextValue = String.valueOf(input.get("context"));
            tracer.record("estado", contextValue);
            log.debug("Extracted estado from loadContext: {}", contextValue);
        }

        // RULE 2: Output with "contextLoaded":true → estado
        // Confirms context was successfully loaded
        if (Boolean.TRUE.equals(output.get("contextLoaded"))) {
            String contextType = (String) output.get("contextType");
            if (contextType != null && !contextType.isEmpty()) {
                tracer.record("estado", contextType);
                log.debug("Extracted estado from contextLoaded output: {}", contextType);
            }
        }

        // RULE 3: Known keys in output → proceso
        // Generic list of common keys that represent process steps
        String[] processKeys = {
            // Medical/appointment related (multi-language)
            "center", "centre", "centro", "centro_medico",
            "specialty", "especialidad", "speciality",
            "appointment_date", "fecha_cita", "date",
            "procedure", "procedimiento",
            // PQRS/complaint related
            "issue_type", "tipo_queja", "complaint_type",
            "department", "departamento",
            // Generic
            "selection", "seleccion", "choice", "opcion"
        };

        for (String key : processKeys) {
            if (output.containsKey(key)) {
                Object value = output.get(key);
                if (value != null) {
                    tracer.record("proceso", String.valueOf(value));
                    log.debug("Extracted proceso from output key '{}': {}", key, value);
                }
            }
        }

        // RULE 4: Nested data extraction (if output has structured data)
        // Example: output = {data: {center: "X", specialty: "Y"}}
        if (output.containsKey("data") && output.get("data") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> nestedData = (Map<String, Object>) output.get("data");
            for (String key : processKeys) {
                if (nestedData.containsKey(key)) {
                    Object value = nestedData.get(key);
                    if (value != null) {
                        tracer.record("proceso", String.valueOf(value));
                        log.debug("Extracted proceso from nested data key '{}': {}", key, value);
                    }
                }
            }
        }
    }

    @Override
    public PromptStartEvent.ToolConfiguration getToolConfiguration() {
        // Merge tool configurations from both handlers
        List<PromptStartEvent.Tool> allTools = new ArrayList<>();

        // Add context loader tools
        PromptStartEvent.ToolConfiguration contextConfig = contextLoader.getToolConfiguration();
        if (contextConfig != null && contextConfig.getTools() != null) {
            allTools.addAll(contextConfig.getTools());
            log.debug("Added {} context loader tools", contextConfig.getTools().size());
        }

        // Add datetime tools
        PromptStartEvent.ToolConfiguration dateTimeConfig = dateTimeHandler.getToolConfiguration();
        if (dateTimeConfig != null && dateTimeConfig.getTools() != null) {
            allTools.addAll(dateTimeConfig.getTools());
            log.debug("Added {} datetime tools", dateTimeConfig.getTools().size());
        }

        log.info("Merged tool configuration: {} total tools available", allTools.size());

        return PromptStartEvent.ToolConfiguration.builder()
            .tools(allTools)
            .build();
    }

    /**
     * Returns the list of available contexts (delegated to context loader).
     * Useful for debugging and testing.
     */
    public static List<String> getAvailableContexts() {
        return DynamicContextLoaderEventHandler.getAvailableContexts();
    }

    /**
     * Returns the configured CLIENT_ID (delegated to context loader).
     * Useful for debugging and testing.
     */
    public static String getClientId() {
        return DynamicContextLoaderEventHandler.getClientId();
    }
}
