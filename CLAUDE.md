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
- `NOVA_PROMPT` - System prompt for Nova Sonic (see System Prompt Configuration section below)
- `NOVA_MAX_TOKENS` - Maximum tokens per response (default: 1024)
- `NOVA_TOP_P` - Top-p sampling parameter (default: 0.9)
- `NOVA_TEMPERATURE` - Temperature for response generation (default: 0.7)

**Debug**:
- `DEBUG_AUDIO_OUTPUT` - Log audio output details (true|false, default: false)

## System Prompt Configuration

### Overview

The system prompt defines the behavior, personality, and conversational flow for Nova Sonic during VoIP calls. The gateway uses a structured prompt approach that guides the AI through different conversational states with clear transitions and instructions.

### Default Prompt

By default, the system loads a structured prompt from `src/main/resources/system-prompt.txt`. This prompt defines:

- **Identity**: The AI's name (Laura), language (Spanish), and role (Virtual Receptionist)
- **Global Rules**: Response timing, tone guidelines, presentation rules, confirmation patterns
- **Conversational States**: A state machine with transitions for:
  - Initial greeting and intent detection
  - Appointment scheduling (with sub-states)
  - Information queries
  - Request/complaint registration
  - Closing and farewell

The default prompt is designed for a Spanish-speaking virtual receptionist that can handle appointments, answer questions, and register requests. It instructs the AI to simulate information lookups (availability, schedules, etc.) by generating realistic example data.

### Prompt Structure

The default prompt follows this structure:

```
# IDENTIDAD
- Name, language, role, and personality description

# REGLAS GLOBALES DE CONVERSACIÓN
- Response timing and length guidelines
- Tone and communication style
- Data handling and confirmation patterns
- Information simulation instructions

# FLUJO CONVERSACIONAL
## ESTADO 1: [State Name]
- ID: state_identifier
- Description: What this state handles
- Instructions: Step-by-step actions
- Examples: Sample phrases
- Transitions: Conditions to move to next states
```

### Customizing the Prompt

**Method 1: Modify the resource file (recommended for development)**

Edit `src/main/resources/system-prompt.txt` directly and rebuild:
```bash
mvn package
```

**Method 2: Override via environment variable (recommended for deployment)**

Set the `NOVA_PROMPT` environment variable with your custom prompt:

```bash
export NOVA_PROMPT="You are a helpful assistant specialized in..."
```

For multi-line prompts from a file:
```bash
export NOVA_PROMPT=$(cat custom-prompt.txt)
```

**Method 3: Load custom prompt in Docker**

Mount your custom prompt file as a volume:
```bash
docker run -e NOVA_PROMPT="$(cat custom-prompt.txt)" ...
```

### Prompt Design Best Practices

1. **Keep responses concise**: Instruct for 2-3 sentence responses to maintain natural conversation flow in voice
2. **Define clear states**: Use a state machine approach with explicit transitions
3. **Provide examples**: Include sample phrases for each state to guide the AI's tone
4. **Simulate data when needed**: For proof-of-concept, instruct the AI to generate realistic example data (dates, times, availability) instead of requiring tool integration
5. **Use natural language**: Write instructions in clear, natural language rather than code-like structures
6. **Language consistency**: Ensure the prompt language matches the expected conversation language

### Example Customization for Different Use Cases

**Technical Support Bot (English):**
```
# IDENTITY
Name: Alex
Language: English
Role: Technical Support Assistant

You are Alex, a friendly technical support assistant. Guide users through troubleshooting
steps clearly and patiently. When you need to check system status or logs, simulate
looking up realistic diagnostic information.

# GLOBAL RULES
- Keep explanations simple and avoid jargon
- Ask one diagnostic question at a time
- Confirm user's issue before providing solutions
...
```

**Appointment Scheduler (Bilingual):**
```
# IDENTIDAD / IDENTITY
Nombre/Name: Sam
Idiomas/Languages: Español e Inglés

Eres Sam, un asistente bilingüe. Detecta el idioma del usuario y responde en ese idioma.
You are Sam, a bilingual assistant. Detect the user's language and respond accordingly.
...
```

### Fallback Behavior

If `system-prompt.txt` cannot be loaded from resources, the system falls back to a simple prompt:
```
"You are a friendly assistant. The user and you will engage in a spoken dialog
exchanging the transcripts of a natural real-time conversation. Keep your responses short,
generally two or three sentences for chatty scenarios."
```

This ensures the application continues to function even if the custom prompt is missing.

### Verification

After starting the application, check the logs for:
```
Successfully loaded system prompt from resources (XXXX characters)
```

Or if using the fallback:
```
Warning: Could not find /system-prompt.txt in resources. Using fallback prompt.
```

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

## Amazon Nova Sonic Limits and Regional Availability

**Service Quotas** (per AWS account per region):
- **RPM (Requests Per Minute)**: 2,000 requests/minute
- **TPM (Tokens Per Minute)**: 2,000,000 tokens/minute
- **Concurrent Sessions**: 20 active sessions maximum
- **Audio Token Rate**: ~150 tokens per second of audio (4,500 tokens/minute)

**Available Regions**:
- **US East (N. Virginia)** - `us-east-1` (primary region)
- **Europe (Stockholm)** - `eu-north-1`
- **Asia Pacific (Tokyo)** - `ap-northeast-1`

⚠️ **Critical Limitation**: The code currently has the region hardcoded to `us-east-1` in `NovaStreamerFactory.java:54`. To deploy in other regions or implement multi-region architecture, this must be changed to use an environment variable (see `docs/OPERACIONES.md` for details).

**Impact on Capacity**:
- Maximum 20 concurrent calls per region regardless of instance size
- To exceed 20 concurrent calls, multi-region deployment is required
- Each region provides independent quota of 20 sessions
- See `docs/INFRAESTRUCTURA-Y-ESCALABILIDAD.md` for multi-region architectures

## Capacity Planning and Cost Analysis

For detailed information on infrastructure sizing, scalability strategies, and cost estimates, see the technical documentation in `/docs`:

- **[Infrastructure and Scalability](docs/INFRAESTRUCTURA-Y-ESCALABILIDAD.md)**: Capacity by instance type, architectural patterns (single-instance, multi-instance with HA, multi-region), network configuration
- **[Costs and Pricing](docs/COSTOS-Y-PRECIOS.md)**: Nova Sonic pricing, cost scenarios (5-500 calls/day), regional comparisons, optimization strategies
- **[Operations](docs/OPERACIONES.md)**: Monitoring, known architectural limitations, troubleshooting guide

**Quick Reference - Capacity by Instance Type**:

| Instance Type | vCPU | RAM | Max Concurrent Calls | Use Case |
|--------------|------|-----|---------------------|----------|
| t3.micro | 2 | 1 GB | 3-5 | Development/POC |
| t3.small | 2 | 2 GB | 10-15 | Testing |
| t3.medium | 2 | 4 GB | 20* | Production (small) |
| c5.large | 2 | 4 GB | 20* | Production (dedicated CPU) |

\* Limited by Nova Sonic quota (20 sessions/region), not by instance resources

**Quick Reference - Cost Estimate** (100 calls/day, 5 min average):
- Nova Sonic: ~$960/month (~93% of total cost)
- Infrastructure (t3.medium): ~$30/month
- Data Transfer & Logs: ~$40/month
- **Total: ~$1,030/month** (~$0.41 per call)

## Important Notes

- Java 9+ compatibility (configured for Java 9 target)
- Uses Maven Shade plugin to create uber-JAR
- Docker image based on Alpine Linux with OpenJDK 21 JRE
- Configuration sources: Environment variables override `.mjsip-ua` file when SIP_SERVER is set
- Greeting audio played on session start (configurable via GREETING_FILENAME)
- Error audio (error.wav) played on stream errors
- All audio files must be WAV format, transcoded to PCM_SIGNED 8kHz 16-bit mono
- **This is a proof of concept**: See `docs/OPERACIONES.md` for list of limitations before considering production deployment
