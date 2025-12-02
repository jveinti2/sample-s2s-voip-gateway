package com.example.s2s.voipgateway;

import com.example.s2s.voipgateway.nova.NovaStreamerFactory;
import com.example.s2s.voipgateway.tracing.CallTracer;
import org.mjsip.config.OptionParser;
import org.mjsip.media.MediaDesc;
import org.mjsip.media.MediaSpec;
import org.mjsip.pool.PortConfig;
import org.mjsip.pool.PortPool;
import org.mjsip.sip.address.NameAddress;
import org.mjsip.sip.address.SipURI;
import org.mjsip.sip.message.SipMessage;
import org.mjsip.sip.provider.SipConfig;
import org.mjsip.sip.provider.SipKeepAlive;
import org.mjsip.sip.provider.SipProvider;
import org.mjsip.sip.provider.SipStack;
import org.mjsip.time.ConfiguredScheduler;
import org.mjsip.time.SchedulerConfig;
import org.mjsip.ua.*;
import org.mjsip.ua.registration.RegistrationClient;
import org.mjsip.ua.streamer.StreamerFactory;
import org.slf4j.LoggerFactory;
import org.zoolu.net.SocketAddress;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Vector;


/**
 * VoIP Gateway/User Agent for Nova Sonic S2S.
 */
public class NovaSonicVoipGateway extends RegisteringMultipleUAS {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(NovaSonicVoipGateway.class);
    // Instance variables
    protected final NovaMediaConfig mediaConfig;
    protected final UAConfig uaConfig;
    private NovaStreamerFactory streamerFactory;
    private RegistrationClient _rc;
    private SipKeepAlive keep_alive;

    // *************************** Public methods **************************

    /**
     * Creates a new UA.
     */
    public NovaSonicVoipGateway(SipProvider sipProvider, PortPool portPool, ServiceOptions serviceConfig,
                                UAConfig uaConfig, NovaMediaConfig mediaConfig) {
        super(sipProvider, portPool, uaConfig, serviceConfig);
        this.mediaConfig = mediaConfig;
        this.uaConfig = uaConfig;
        streamerFactory = new NovaStreamerFactory(this.mediaConfig);
        registerWithKeepAlive();
    }

    /**
     * Disable RegisteringMultipleUAS.register(), which gets called from the constructor.
     * We need _rc to schedule keep-alives, but it's private in the parent class.
     */
    @Override
    public void register() { }

    /**
     * SIP REGISTER with keep-alive packets sent on a schedule.
     */
    public void registerWithKeepAlive() {
        LOG.info("Registering with {}...", this.uaConfig.getRegistrar());
        if (this.uaConfig.isRegister()) {
            this._rc = new RegistrationClient(this.sip_provider, this.uaConfig, this);
            this._rc.loopRegister(this.uaConfig);
        }
        scheduleKeepAlive(uaConfig.getKeepAliveTime());
    }

    private void scheduleKeepAlive(long keepAliveTime) {
        if (keepAliveTime > 0L) {
            SipURI targetUri = this.sip_provider.hasOutboundProxy() ? this.sip_provider.getOutboundProxy() : _rc.getTargetAOR().getAddress().toSipURI();
            String targetHost = targetUri.getHost();
            int targetPort = targetUri.getPort();
            if (targetPort < 0) {
                targetPort = this.sip_provider.sipConfig().getDefaultPort();
            }

            SocketAddress targetSoAddr = new SocketAddress(targetHost, targetPort);
            if (this.keep_alive != null && this.keep_alive.isRunning()) {
                this.keep_alive.halt();
            }

            this.keep_alive = new SipKeepAlive(this.sip_provider, targetSoAddr, (SipMessage)null, keepAliveTime);
            LOG.info("Keep-alive started");
        }
    }

    @Override
    public void unregister() {
        LOG.info("Unregistering with {}...", this.uaConfig.getRegistrar());
        if (this._rc != null) {
            this._rc.unregister();
            this._rc.halt();
            this._rc = null;
        }
    }

    @Override
    protected UserAgentListener createCallHandler(SipMessage msg) {
        register();

        // Extract real SIP Call-ID from message
        String sipCallId = msg.getCallIdHeader().getCallId();

        // Extract ALL SIP headers dynamically
        Map<String, String> sipHeaders = extractAllSipHeaders(msg);

        // Log all headers for debugging
        logSipHeadersDetailed(sipCallId, sipHeaders);

        return new UserAgentListenerAdapter() {
            @Override
            public void onUaIncomingCall(UserAgent ua, NameAddress callee, NameAddress caller,
                                         MediaDesc[] media_descs) {
                LOG.info("Incoming call from: {} to: {}", caller.getAddress(), callee.getAddress());

                // Add calculated variables to the map
                sipHeaders.put("sip_call_id", sipCallId);
                sipHeaders.put("ani", extractPhoneNumber(caller));
                sipHeaders.put("dnis", extractPhoneNumber(callee));
                sipHeaders.put("client_id", System.getenv().getOrDefault("CLIENT_ID", "keralty"));

                // Create call tracer with all variables
                CallTracer tracer = new CallTracer(sipHeaders);

                // Create media agent with tracer
                ua.accept(new MediaAgent(mediaConfig.getMediaDescs(), streamerFactory.withTracer(tracer)));
            }
        };
    }

    /**
     * Extracts ALL headers from SIP message dynamically.
     * Captures custom headers sent by provider (e.g., X-Client-Name, X-Session-ID).
     *
     * @param msg SIP message
     * @return Map with all headers (key: header name in lowercase, value: header value)
     */
    private Map<String, String> extractAllSipHeaders(SipMessage msg) {
        Map<String, String> headers = new HashMap<>();

        try {
            // Get all headers from SIP message
            Vector<?> allHeaders = msg.getHeaders();

            for (Object headerObj : allHeaders) {
                try {
                    // mjSIP uses org.mjsip.sip.header.Header interface
                    // Each header has getName() and getValue() methods
                    String headerName = (String) headerObj.getClass().getMethod("getName").invoke(headerObj);
                    String headerValue = (String) headerObj.getClass().getMethod("getValue").invoke(headerObj);

                    if (headerName != null && headerValue != null) {
                        String key = headerName.toLowerCase();

                        // Handle duplicate headers (e.g., multiple Via headers)
                        if (headers.containsKey(key)) {
                            headers.put(key, headers.get(key) + "," + headerValue);
                        } else {
                            headers.put(key, headerValue);
                        }
                    }
                } catch (Exception e) {
                    LOG.debug("Could not extract header from object: {}", headerObj.getClass().getName());
                }
            }

            LOG.debug("Extracted {} SIP headers from message", headers.size());

        } catch (Exception e) {
            LOG.error("Error extracting SIP headers: {}", e.getMessage(), e);
        }

        return headers;
    }

    /**
     * Logs all SIP headers in a formatted block for debugging.
     *
     * @param sipCallId SIP Call-ID
     * @param headers Map of headers
     */
    private void logSipHeadersDetailed(String sipCallId, Map<String, String> headers) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n========================================\n");
        sb.append("SIP HEADERS RECEIVED (call_id: ").append(sipCallId).append(")\n");
        sb.append("========================================\n");

        // Sort alphabetically for easier reading
        headers.keySet().stream().sorted().forEach(key -> {
            sb.append(String.format("  %-25s: %s\n", key, headers.get(key)));
        });

        sb.append("========================================\n");
        sb.append("Total headers: ").append(headers.size()).append("\n");
        sb.append("========================================");

        LOG.info(sb.toString());
    }

    /**
     * Extracts phone number from SIP NameAddress.
     * Handles formats like "sip:3001234567@server.com" or "<sip:+573001234567@server.com>"
     *
     * @param addr NameAddress containing the phone number
     * @return Extracted phone number, or the full address string if extraction fails
     */
    private String extractPhoneNumber(NameAddress addr) {
        if (addr == null || addr.getAddress() == null) {
            return "unknown";
        }

        String fullAddress = addr.getAddress().toString();
        // Remove "sip:" prefix and everything after "@"
        String extracted = fullAddress.replaceAll("^sip:", "").replaceAll("@.*$", "");

        // If result is empty or same as input, return original
        return extracted.isEmpty() ? fullAddress : extracted;
    }

    /**
     * The main method.
     */
    public static void main(String[] args) {
        println("mjSIP UserAgent " + SipStack.version);
        SipConfig sipConfig = new SipConfig();
        UAConfig uaConfig = new UAConfig();
        SchedulerConfig schedulerConfig = new SchedulerConfig();
        PortConfig portConfig = new PortConfig();
        ServiceConfig serviceConfig = new ServiceConfig();
        NovaMediaConfig mediaConfig = new NovaMediaConfig();
        Map<String, String> environ = System.getenv();
        mediaConfig.setNovaVoiceId(environ.getOrDefault("NOVA_VOICE_ID","en_us_matthew"));

        // Load prompt: prioritize NOVA_PROMPT env var, otherwise load from resources based on CLIENT_ID
        if (isConfigured(environ.get("NOVA_PROMPT"))) {
            mediaConfig.setNovaPrompt(environ.get("NOVA_PROMPT"));
            LOG.info("Using NOVA_PROMPT from environment variable");
        } else {
            String clientId = environ.getOrDefault("CLIENT_ID", "keralty");
            String basePrompt = NovaMediaConfig.loadBasePrompt(clientId);
            mediaConfig.setNovaPrompt(basePrompt);
            LOG.info("Loaded base prompt for CLIENT_ID: {}", clientId);
        }

        if (isConfigured(environ.get("SIP_SERVER"))) {
            configureFromEnvironment(environ, uaConfig, mediaConfig, portConfig, sipConfig);
        } else {
            OptionParser.parseOptions(args, ".mjsip-ua", sipConfig, uaConfig, schedulerConfig, mediaConfig, portConfig, serviceConfig);
        }

        sipConfig.normalize();
        uaConfig.normalize(sipConfig);

        SipProvider sipProvider = new SipProvider(sipConfig, new ConfiguredScheduler(schedulerConfig));
        NovaSonicVoipGateway gateway = new NovaSonicVoipGateway(sipProvider, portConfig.createPool(), serviceConfig, uaConfig, mediaConfig);
    }

    /**
     * Checks if a string is configured.
     * @param str The string
     * @return true if the string is not null and not empty, otherwise false
     */
    private static boolean isConfigured(String str) {
        return str != null && !str.isEmpty();
    }

    private static void configureFromEnvironment(Map<String, String> environ, UAConfig uaConfig,
                                                 NovaMediaConfig mediaConfig, PortConfig portConfig,
                                                 SipConfig sipConfig) {
        uaConfig.setRegistrar(new SipURI(environ.get("SIP_SERVER")));
        uaConfig.setSipUser(environ.get("SIP_USER"));
        uaConfig.setAuthUser(environ.get("AUTH_USER"));
        uaConfig.setAuthPasswd(environ.get("AUTH_PASSWORD"));
        uaConfig.setAuthRealm(environ.get("AUTH_REALM"));
        uaConfig.setDisplayName(environ.get("DISPLAY_NAME"));
        if (isConfigured(environ.get("MEDIA_ADDRESS"))) {
            uaConfig.setMediaAddr(environ.get("MEDIA_ADDRESS"));
        }
        uaConfig.setKeepAliveTime(Long.parseLong(environ.getOrDefault("SIP_KEEPALIVE_TIME","60000")));
        uaConfig.setNoPrompt(true);
        mediaConfig.setMediaDescs(createDefaultMediaDescs());
        if (isConfigured(environ.get("MEDIA_PORT_BASE"))) {
            portConfig.setMediaPort(Integer.parseInt(environ.get("MEDIA_PORT_BASE")));
        }
        if (isConfigured(environ.get("MEDIA_PORT_COUNT"))) {
            portConfig.setPortCount(Integer.parseInt(environ.get("MEDIA_PORT_COUNT")));
        }
        sipConfig.setLogAllPackets(environ.getOrDefault("DEBUG_SIP","true").equalsIgnoreCase("true"));
        if (isConfigured(environ.get("SIP_VIA_ADDR"))) {
            sipConfig.setViaAddrIPv4(environ.get("SIP_VIA_ADDR"));
        }
    }

    /**
     * Prints a message to standard output.
     */
    protected static void println(String str) {
        System.out.println(str);
    }

    /**
     * Creates the default media descriptions.
     * @return
     */
    private static MediaDesc[] createDefaultMediaDescs() {
        return new MediaDesc[]{new MediaDesc("audio",
                4000,
                "RTP/AVP",
                new MediaSpec[]{
                        new MediaSpec(0,
                                "PCMU",
                                8000,
                                1,
                                160)})};
    }

}