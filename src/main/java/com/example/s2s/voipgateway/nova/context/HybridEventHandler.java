package com.example.s2s.voipgateway.nova.context;

import com.example.s2s.voipgateway.nova.AbstractNovaS2SEventHandler;
import com.example.s2s.voipgateway.nova.event.NovaSonicEvent;
import com.example.s2s.voipgateway.nova.event.PromptStartEvent;
import com.example.s2s.voipgateway.nova.observer.InteractObserver;
import com.example.s2s.voipgateway.nova.tools.DateTimeNovaS2SEventHandler;
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

    public HybridEventHandler() {
        super();
        this.contextLoader = new DynamicContextLoaderEventHandler();
        this.dateTimeHandler = new DateTimeNovaS2SEventHandler();
        log.info("HybridEventHandler initialized with context loader (CLIENT_ID: {}) and datetime tools",
            DynamicContextLoaderEventHandler.getClientId());
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
