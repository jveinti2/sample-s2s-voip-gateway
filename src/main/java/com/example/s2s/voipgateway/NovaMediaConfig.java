package com.example.s2s.voipgateway;

import com.example.s2s.voipgateway.nova.context.PromptFragmentLoader;
import org.mjsip.ua.MediaConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NovaMediaConfig extends MediaConfig {
    private static final Logger log = LoggerFactory.getLogger(NovaMediaConfig.class);
    private static final String DEFAULT_VOICE_ID = "en_us_matthew";
    private static final String DEFAULT_CLIENT_ID = "keralty";
    private static final String DEFAULT_PROMPT = "You are a friendly assistant. The user and you will engage in a spoken dialog " +
            "exchanging the transcripts of a natural real-time conversation. Keep your responses short, " +
            "generally two or three sentences for chatty scenarios.";
    private static final int DEFAULT_MAX_TOKENS = 1024;
    private static final float DEFAULT_NOVA_TOP_P = 0.9F;
    private static final float DEFAULT_NOVA_TEMPERATURE = 0.7F;
    private String novaVoiceId = DEFAULT_VOICE_ID;
    private String novaPrompt = DEFAULT_PROMPT;
    private int novaMaxTokens = DEFAULT_MAX_TOKENS;
    private float novaTopP = DEFAULT_NOVA_TOP_P;
    private float novaTemperature = DEFAULT_NOVA_TEMPERATURE;

    public String getNovaVoiceId() {
        return novaVoiceId;
    }

    public void setNovaVoiceId(String novaVoiceId) {
        this.novaVoiceId = novaVoiceId;
    }

    public String getNovaPrompt() {
        return novaPrompt;
    }

    public void setNovaPrompt(String novaPrompt) {
        this.novaPrompt = novaPrompt;
    }

    public int getNovaMaxTokens() {
        return novaMaxTokens;
    }

    public void setNovaMaxTokens(int novaMaxTokens) {
        this.novaMaxTokens = novaMaxTokens;
    }

    public float getNovaTopP() {
        return novaTopP;
    }

    public void setNovaTopP(float novaTopP) {
        this.novaTopP = novaTopP;
    }

    public float getNovaTemperature() {
        return novaTemperature;
    }

    public void setNovaTemperature(float novaTemperature) {
        this.novaTemperature = novaTemperature;
    }

    /**
     * Loads the base prompt for the specified client from resources.
     * Falls back to default client if specified client not found.
     * Falls back to hardcoded DEFAULT_PROMPT if no resources found.
     *
     * @param clientId The client ID (e.g., "keralty", "colmedica")
     * @return The base prompt content
     */
    public static String loadBasePrompt(String clientId) {
        String clientToLoad = (clientId == null || clientId.isEmpty()) ? DEFAULT_CLIENT_ID : clientId;
        String resourcePath = "/prompts/" + clientToLoad + "/base-prompt.txt";

        log.info("Loading base prompt for client: {} from: {}", clientToLoad, resourcePath);

        String prompt = PromptFragmentLoader.loadFragment(resourcePath);

        if (prompt.isEmpty()) {
            log.warn("Base prompt not found for client: {}. Trying default client...", clientToLoad);
            resourcePath = "/prompts/default/base-prompt.txt";
            prompt = PromptFragmentLoader.loadFragment(resourcePath);

            if (prompt.isEmpty()) {
                log.warn("Default base prompt not found either. Using hardcoded fallback.");
                return DEFAULT_PROMPT;
            } else {
                log.info("Successfully loaded default base prompt ({} characters)", prompt.length());
            }
        } else {
            log.info("Successfully loaded base prompt for client '{}' ({} characters)", clientToLoad, prompt.length());
        }

        return prompt;
    }
}
