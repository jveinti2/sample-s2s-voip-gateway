# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Nova S2S VoIP Gateway - A SIP endpoint that acts as a gateway to Amazon Nova Sonic speech-to-speech. This is a Java Maven project that allows you to call a phone number and talk to Nova Sonic via VoIP. **This is a proof of concept and not production-ready.**

## Build and Development Commands

### Maven Build
```bash
mvn package
```
Compiles the project and creates `s2s-voip-gateway-<version>.jar` in the `target/` directory using Maven Shade plugin.

### Quick Run (Compile + Execute)
```bash
./run.sh
```
Compiles and runs the main class: `com.example.s2s.voipgateway.NovaSonicVoipGateway`

### Maven Configuration Required
Before building, you must configure `~/.m2/settings.xml` with GitHub credentials (classic API token) to access the mjSIP dependency from GitHub Maven repository:
```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>YOUR_USERNAME</username>
      <password>YOUR_AUTH_TOKEN</password>
    </server>
  </servers>
</settings>
```

### CDK Deployment

**EC2 Instance (for development/testing):**
```bash
cd cdk-ec2-instance
npm install
cdk bootstrap
cdk deploy
cdk destroy  # cleanup
```
Before deploying: Update `cdk-ec2-instance/bin/cdk.ts` with your `keyPairName` and optionally `vpcId`.

**ECS Container (for production-like deployment):**
1. Build the Maven project first
2. Copy `target/s2s-voip-gateway-<version>.jar` to `docker/` directory
3. Copy `cdk-ecs/cdk.context.json.template` to `cdk-ecs/cdk.context.json` and configure
4. Deploy:
```bash
cd cdk-ecs
npm install
cdk bootstrap
cdk deploy
cdk destroy  # cleanup
```

## Architecture

### High-Level Flow
1. Application registers as SIP user agent with SIP server
2. Upon receiving incoming call, answers and establishes RTP media session
3. Creates Amazon Nova Sonic session via AWS Bedrock Runtime
4. Bridges audio bidirectionally: RTP ↔ Nova Sonic
5. Audio from caller (RTP) → transcoded → sent to Nova Sonic
6. Audio from Nova Sonic → transcoded → sent to caller (RTP)

### Core Components

**Entry Point**: `NovaSonicVoipGateway.java` (main class)
- Extends `RegisteringMultipleUAS` from mjSIP library
- Handles SIP registration with keep-alive packets
- Creates call handlers for incoming calls
- Configurable via `.mjsip-ua` file OR environment variables (if `SIP_SERVER` is set)

**Nova Integration**: `NovaStreamerFactory.java`
- Factory for creating media streamers that connect to Amazon Nova Sonic
- Instantiates `BedrockRuntimeAsyncClient` with HTTP/2 and Netty async client
- Creates session start events, prompt configurations, and tool support
- Bridges `AudioTransmitter` (NovaSonicAudioInput) and `AudioReceiver` (NovaSonicAudioOutput)
- Tool configuration added via `NovaS2SEventHandler.getToolConfiguration()`

**Audio Processing**:
- `NovaSonicAudioInput` - Transmits audio from RTP to Nova Sonic
- `NovaSonicAudioOutput` - Receives audio from Nova Sonic and sends to RTP
- Audio transcoding handled by `UlawToPcmTranscoder` and `PcmToULawTranscoder`
- Queued audio buffering via `QueuedUlawInputStream`

**Tool System**:
- `AbstractNovaS2SEventHandler` - Base class for tool implementations
- Handles tool invocation lifecycle: receives tool use events, processes them, returns results
- Example: `DateTimeNovaS2SEventHandler` provides date/time tools to Nova Sonic
- Tool set instantiated in `NovaStreamerFactory.createMediaStreamer()`
- To add new tools: extend `AbstractNovaS2SEventHandler`, implement `handleToolInvocation()`, and update factory

**Event Handling**:
- `NovaS2SBedrockInteractClient` - Manages Bedrock streaming interaction
- `NovaS2SResponseHandler` - Processes streaming responses from Nova Sonic
- Event types: SessionStart, PromptStart, ContentStart/End, AudioInput/Output, ToolUse, ToolResult
- Observer pattern with `InteractObserver` for bidirectional streaming

### Package Structure
- `com.example.s2s.voipgateway` - Main gateway and configuration classes
- `com.example.s2s.voipgateway.nova` - Nova Sonic integration (client, handlers, factory)
- `com.example.s2s.voipgateway.nova.event` - Event POJOs for Nova S2S protocol
- `com.example.s2s.voipgateway.nova.io` - Audio I/O streams
- `com.example.s2s.voipgateway.nova.transcode` - Audio format transcoding (PCM ↔ μ-law)
- `com.example.s2s.voipgateway.nova.observer` - Observer pattern for streaming
- `com.example.s2s.voipgateway.nova.tools` - Tool implementations (example: DateTime)
- `com.example.s2s.voipgateway.constants` - Audio configuration constants

## Environment Variables

Configuration priority: If `SIP_SERVER` is set → use environment variables, otherwise use `.mjsip-ua` file.

**SIP Configuration**:
- `SIP_SERVER` - Hostname/IP of SIP server (triggers env var mode)
- `SIP_USER` - SIP username
- `SIP_VIA_ADDR` - Address for Via field in SIP packets (auto-detected if not set)
- `AUTH_USER` - Authentication username
- `AUTH_PASSWORD` - Authentication password
- `AUTH_REALM` - SIP authentication realm
- `DISPLAY_NAME` - Display name for SIP address
- `SIP_KEEPALIVE_TIME` - Keep-alive frequency in milliseconds (default: 60000)
- `DEBUG_SIP` - Enable/disable SIP packet logging (true|false, default: true)

**Media Configuration**:
- `MEDIA_ADDRESS` - IP address for RTP media traffic (auto-detected if not set)
- `MEDIA_PORT_BASE` - First RTP port (default: 10000)
- `MEDIA_PORT_COUNT` - Size of RTP port pool (default: 10000)
- `GREETING_FILENAME` - WAV file to play as greeting (absolute path or classpath, default: hello-how.wav)

**Nova Configuration**:
- `NOVA_VOICE_ID` - Amazon Nova Sonic voice ID (default: en_us_matthew)
- `NOVA_PROMPT` - System prompt for Nova Sonic (see NovaMediaConfig.java for default)

**Debug**:
- `DEBUG_AUDIO_OUTPUT` - Log audio output details (true|false, default: false)

## Networking Requirements

**Inbound Security Group Rules**:
- UDP port 5060 (SIP signaling)
- UDP ports 10000-20000 (RTP media, configurable via MEDIA_PORT_BASE/COUNT)

**Outbound**: Allow all

Note: mjSIP lacks uPNP, ICE, or STUN capabilities - instance must have proper security groups and direct network access.

## Key Dependencies

- **mjSIP** (v2.0.5) - SIP user agent library (fork from https://github.com/haumacher/mjSIP) - GPLv2 license
- **AWS SDK for Java v2** - Bedrock Runtime async client
- **Jackson** - JSON processing
- **Lombok** - Annotation processor for boilerplate reduction
- **RxJava/Reactor** - Reactive streams for async processing
- **Logback** - Logging framework

## Important Notes

- Java 9+ compatibility (configured for Java 9 target)
- Uses Maven Shade plugin to create uber-JAR
- Docker image based on Alpine Linux with OpenJDK 21 JRE
- Configuration sources: Environment variables override `.mjsip-ua` file when SIP_SERVER is set
- Greeting audio played on session start (configurable via GREETING_FILENAME)
- Error audio (error.wav) played on stream errors
- All audio files must be WAV format, transcoded to PCM_SIGNED 8kHz 16-bit mono
