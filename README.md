# Gateway VoIP Nova S2S

Este proyecto contiene una implementación de un endpoint SIP que actúa como gateway hacia Nova Sonic speech-to-speech de Amazon. En otras palabras, puedes llamar a un número de teléfono y hablar con Nova Sonic.

**⚠️ Nota Importante:** Este es solo una prueba de concepto y no debe considerarse código listo para producción.

---

## 🚀 Inicio Rápido

Para instrucciones completas de instalación, configuración y despliegue, consulta:

**📖 [GUÍA DE EJECUCIÓN (RUN_INSTRUCTIONS.md)](docs/RUN_INSTRUCTIONS.md)**

---

## Tabla de Contenidos

- [¿Cómo funciona?](#cómo-funciona)
- [Arquitectura](#arquitectura)
- [Dependencias de Terceros Importantes](#dependencias-de-terceros-importantes)
- [Guía para Desarrolladores](#guía-para-desarrolladores)
- [Documentación Técnica](#documentación-técnica)
- [Licencia](#licencia)

## ¿Cómo funciona?

Esta aplicación actúa como un agente de usuario SIP. Cuando inicia, se registra con un servidor SIP. Al recibir una llamada, responderá, establecerá la sesión de medios (sobre RTP), iniciará una sesión con Nova Sonic y puenteará el audio entre RTP y Nova Sonic. El audio recibido vía RTP se envía a Nova Sonic y el audio recibido de Nova Sonic se envía a la persona que llama vía RTP.

### Flujo de Comunicación

![](flow.png)

### Arquitectura de Despliegue

El gateway puede desplegarse en diferentes configuraciones según tus necesidades:

- **Ejecución local** con `./run.sh` para desarrollo rápido
- **Instancia EC2** simple para testing y desarrollo
- **ECS con EC2 en modo host** para entornos production-like con contenedores

![](architecture.png)

## Arquitectura

### Flujo de Alto Nivel

1. La aplicación se registra como agente de usuario SIP con el servidor SIP
2. Al recibir una llamada entrante, responde y establece la sesión de medios RTP
3. Crea una sesión con Amazon Nova Sonic vía AWS Bedrock Runtime
4. Puentea audio bidireccionalmente: RTP ↔ Nova Sonic
5. Audio del llamante (RTP) → transcodificado → enviado a Nova Sonic
6. Audio de Nova Sonic → transcodificado → enviado al llamante (RTP)

### Componentes Principales

#### Punto de Entrada
**`NovaSonicVoipGateway.java`** - Clase principal
- Extiende `RegisteringMultipleUAS` de la librería mjSIP
- Maneja el registro SIP con paquetes keep-alive
- Crea handlers de llamadas para llamadas entrantes
- Configurable vía archivo `.mjsip-ua` O variables de entorno (si `SIP_SERVER` está configurado)

#### Integración con Nova
**`NovaStreamerFactory.java`** - Factory para crear streamers de medios
- Instancia `BedrockRuntimeAsyncClient` con HTTP/2 y cliente async Netty
- Crea eventos de inicio de sesión, configuraciones de prompt y soporte de herramientas
- Puentea `AudioTransmitter` (NovaSonicAudioInput) y `AudioReceiver` (NovaSonicAudioOutput)
- Configuración de herramientas agregada vía `NovaS2SEventHandler.getToolConfiguration()`

#### Procesamiento de Audio
- **`NovaSonicAudioInput`** - Transmite audio desde RTP hacia Nova Sonic
- **`NovaSonicAudioOutput`** - Recibe audio de Nova Sonic y envía a RTP
- **Transcodificación** - `UlawToPcmTranscoder` y `PcmToULawTranscoder`
- **Buffering** - `QueuedUlawInputStream` para cola de audio

#### Sistema de Herramientas
- **`AbstractNovaS2SEventHandler`** - Clase base para implementaciones de herramientas
- **`HybridEventHandler`** - Combina múltiples tipos de herramientas (carga de contexto + herramientas funcionales)
- **`DynamicContextLoaderEventHandler`** - Carga fragmentos de prompts bajo demanda (optimiza uso de tokens)
- **`DateTimeNovaS2SEventHandler`** - Proporciona utilidades de fecha/hora

**Flujo de invocación de herramientas:** Nova Sonic solicita → handler procesa → resultados retornados a la conversación

**Dos tipos de herramientas:**
- **Herramientas de contexto**: Cargan fragmentos de prompts dinámicamente (ej: `loadContext`)
- **Herramientas funcionales**: Ejecutan operaciones y retornan datos (ej: `getDateTool`)

**Agregar nuevos contextos:** Solo agrega archivo `context-{nombre}.txt` (auto-descubierto)

**Agregar herramientas funcionales:** Extiende `AbstractNovaS2SEventHandler` e integra en `HybridEventHandler`

#### Manejo de Eventos
- **`NovaS2SBedrockInteractClient`** - Gestiona la interacción streaming con Bedrock
- **`NovaS2SResponseHandler`** - Procesa respuestas streaming de Nova Sonic
- **Tipos de eventos**: SessionStart, PromptStart, ContentStart/End, AudioInput/Output, ToolUse, ToolResult
- Patrón observer con `InteractObserver` para streaming bidireccional

### Estructura de Paquetes

```
com.example.s2s.voipgateway
├── NovaSonicVoipGateway.java                    # Clase principal, entry point
├── nova/
│   ├── NovaStreamerFactory.java                 # Factory de integración Nova
│   ├── NovaS2SBedrockInteractClient.java        # Cliente Bedrock streaming
│   ├── NovaS2SResponseHandler.java              # Handler de respuestas
│   ├── context/                                 # Sistema de carga dinámica de prompts
│   │   ├── PromptFragmentLoader.java            # Utilidad de lectura de prompts
│   │   ├── DynamicContextLoaderEventHandler.java # Auto-descubrimiento de contextos
│   │   └── HybridEventHandler.java              # Merge de context + functional tools
│   ├── event/                                   # POJOs de eventos Nova S2S
│   ├── io/                                      # Streams I/O de audio
│   ├── transcode/                               # Transcodificación PCM ↔ μ-law
│   ├── observer/                                # Patrón observer para streaming
│   └── tools/                                   # Implementaciones de herramientas
│       └── DateTimeNovaS2SEventHandler.java     # Ejemplo: herramientas de fecha/hora
├── constants/                                   # Constantes de configuración de audio
```

### Sistema de Prompts Multi-Cliente

El gateway implementa un **sistema de prompts multi-cliente con carga dinámica** que optimiza el consumo de tokens:

1. Envía un **prompt base ultra-minimal** con identidad core, 4 reglas críticas y detección de intención (~280 tokens)
2. Carga **fragmentos de contexto** detallados con reglas y recursos específicos del flujo solo cuando se necesitan (on-demand vía tools)
3. Soporta **múltiples clientes** vía variable de entorno `CLIENT_ID` con configuraciones de prompts independientes
4. Distribuye reglas entre contextos - cada flujo solo carga sus reglas y recursos relevantes

**Arquitectura de reducción de tokens:**
- Reducción inicial: **81-85%** vs prompts monolíticos
- Reducción total después de carga de contexto: **60-70%**

**Estructura de directorios:**
```
src/main/resources/prompts/
├── keralty/                    # Cliente específico
│   ├── base-prompt.txt         # Prompt inicial (identidad + reglas + estado 1)
│   ├── context-citas.txt       # Cargado on-demand para citas
│   ├── context-pqrs.txt        # Cargado on-demand para quejas
│   └── context-imagenes.txt    # Cargado on-demand para diagnósticos
├── {otro-cliente}/
│   └── ...
```

**Configuración:** Ver sección de variables de entorno en [RUN_INSTRUCTIONS.md](docs/RUN_INSTRUCTIONS.md)

## Dependencias de Terceros Importantes

Este proyecto utiliza un fork del proyecto mjSIP, que se puede encontrar en https://github.com/haumacher/mjSIP, el cual está licenciado bajo **GPLv2**.

### Stack Tecnológico

- **mjSIP (v2.0.5)** - Librería de agente de usuario SIP (fork de mjSIP) - Licencia GPLv2
- **AWS SDK for Java v2** - Cliente async de Bedrock Runtime
- **Jackson** - Procesamiento JSON
- **Lombok** - Procesador de anotaciones para reducir boilerplate
- **RxJava/Reactor** - Streams reactivos para procesamiento async
- **Logback** - Framework de logging

### Compatibilidad

- **Java 9+** (configurado para target Java 9, funciona con versiones superiores)
- **Maven Shade Plugin** para crear uber-JAR con todas las dependencias
- **Docker** basado en Alpine Linux con OpenJDK 21 JRE

## Guía para Desarrolladores

### Punto de Entrada

El punto de entrada para la aplicación es **`NovaSonicVoipGateway.java`**. Esta clase contiene el método `main` y configura el agente de usuario basándose en lo que encuentra en las variables de entorno.

### Integración con Nova

El punto de entrada principal para la integración con Nova está en **`NovaStreamerFactory.java`**, donde se instancia el cliente de Bedrock y se establecen los flujos de audio.

### Desarrollar Nuevas Herramientas

Por defecto, el gateway incluye un conjunto de herramientas que le da a Nova Sonic la capacidad de recuperar la fecha y hora, pero esto puede extenderse para hacer mucho más.

**Ubicación de herramientas de ejemplo:**
`com.example.s2s.voipgateway.nova.tools`

**Pasos para crear nuevas herramientas:**

1. **Extiende `AbstractNovaS2SEventHandler`** e implementa la funcionalidad deseada
2. Ver el javadoc en `AbstractNovaS2SEventHandler` para más información
3. **Punto de partida fácil**: Copiar `DateTimeNovaS2SEventHandler` a un nuevo archivo y reemplazar las herramientas con algo relevante a tu caso de uso
4. **Instanciar en `NovaStreamerFactory.createMediaStreamer()`**: Actualiza el `NovaS2SEventHandler` para instanciar tu nueva clase

### Agregar Nuevos Contextos de Prompts

Para agregar un nuevo contexto que Nova Sonic puede cargar dinámicamente:

1. Crea `src/main/resources/prompts/{CLIENT_ID}/context-{nombre}.txt`
2. Actualiza `base-prompt.txt` para mencionar el nuevo contexto en las transiciones
3. Recompila - el contexto será auto-descubierto al inicio

**No se requieren cambios de código** - los contextos se descubren automáticamente al iniciar.

### Agregar un Nuevo Cliente

1. Crea `src/main/resources/prompts/{nuevo-cliente}/`
2. Agrega `base-prompt.txt` y archivos `context-*.txt`
3. Despliega con `export CLIENT_ID=nuevo-cliente`

**No se requieren cambios de código** - los clientes se cargan dinámicamente.

## Documentación Técnica

Para información detallada sobre planificación de infraestructura, análisis de costos, escalabilidad y operaciones, consulta la documentación técnica en la carpeta `/docs`:

### Documentación Operacional

- **[RUN_INSTRUCTIONS.md](docs/RUN_INSTRUCTIONS.md)** - **Guía completa de instalación, configuración y despliegue**
- **[INFRAESTRUCTURA-Y-ESCALABILIDAD.md](docs/INFRAESTRUCTURA-Y-ESCALABILIDAD.md)** - Capacidad por tipo de instancia, límites técnicos, arquitecturas de despliegue (single-instance y multi-instancia), estrategias multi-región
- **[COSTOS-Y-PRECIOS.md](docs/COSTOS-Y-PRECIOS.md)** - Pricing de Nova Sonic, análisis de costos por escenario, comparativas por región y tipo de instancia
- **[OPERACIONES.md](docs/OPERACIONES.md)** - Monitoreo, limitaciones arquitecturales actuales, troubleshooting y guía operacional

### Referencia Rápida

**Capacidad por Instancia:**

| Instance Type | vCPU | RAM | Max Concurrent Calls | Caso de Uso |
|--------------|------|-----|---------------------|-------------|
| t3.micro | 2 | 1 GB | 3-5 | Desarrollo/POC |
| t3.small | 2 | 2 GB | 10-15 | Testing |
| t3.medium | 2 | 4 GB | 20* | Producción (pequeña) |
| c5.large | 2 | 4 GB | 20* | Producción (CPU dedicada) |

\* Limitado por quota de Nova Sonic (20 sesiones/región), no por recursos de instancia

**Estimación de Costos** (100 llamadas/día, 5 min promedio):
- Nova Sonic: ~$960/mes (~93% del costo total)
- Infraestructura (t3.medium): ~$30/mes
- Data Transfer & Logs: ~$40/mes
- **Total: ~$1,030/mes** (~$0.41 por llamada)

## Licencia

**Licencia MIT-0**. Ver el archivo [LICENSE](LICENSE) para más detalles.

**Nota sobre mjSIP:** La librería mjSIP está licenciada bajo GPLv2. Ver [mjSIP repository](https://github.com/haumacher/mjSIP) para más información.
