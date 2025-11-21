# Infraestructura y Escalabilidad

Este documento proporciona información técnica para la planificación de infraestructura y estrategias de escalabilidad del Gateway VoIP Nova S2S.

## Tabla de Contenidos

- [Capacidad y Recursos](#capacidad-y-recursos)
- [Arquitecturas de Despliegue](#arquitecturas-de-despliegue)
- [Estrategias Multi-Región](#estrategias-multi-región)
- [Configuración de Red](#configuración-de-red)

---

## Capacidad y Recursos

### Consumo de Recursos por Llamada Activa

Cada llamada simultánea consume aproximadamente:

| Recurso | Consumo Estimado | Notas |
|---------|------------------|-------|
| **Memoria RAM** | 50-100 MB | Incluye buffers de audio, sesión RTP, cliente Bedrock, RxJava processors |
| **CPU** | 10-20% por vCPU | Durante conversación activa (transcodificación + serialización JSON) |
| **Ancho de Banda (Red)** | ~200 kbps bidireccional | RTP: ~64 kbps + Bedrock API: ~128 kbps |
| **Puertos RTP** | 2 puertos UDP | 1 para audio entrante, 1 para audio saliente |

### Límites Técnicos de Amazon Nova Sonic

| Límite | Valor | Descripción |
|--------|-------|-------------|
| **RPM (Requests Per Minute)** | 2,000 | Solicitudes máximas por minuto por cuenta/región |
| **TPM (Tokens Per Minute)** | 2,000,000 | Tokens máximos procesables por minuto |
| **Sesiones Concurrentes** | 20 | Sesiones activas simultáneas con Nova Sonic por cuenta/región |
| **Regiones Disponibles** | 3 | US East (N. Virginia), Europe (Stockholm), Asia Pacific (Tokyo) |

⚠️ **Importante**: El límite de 20 sesiones concurrentes es el cuello de botella principal para escalabilidad en una sola región.

### Límites de la Aplicación

| Componente | Límite | Configuración |
|------------|--------|---------------|
| **Pool de Puertos RTP** | 10,000 puertos | `MEDIA_PORT_BASE=10000`, `MEDIA_PORT_COUNT=10000` |
| **Llamadas Máximas Teóricas** | ~5,000 | Limitado por pool de puertos (10,000 / 2 puertos por llamada) |
| **Concurrencia Cliente Bedrock** | 20 conexiones | Configurado en `NovaStreamerFactory.java:49` |
| **Buffer de Audio** | 50,000 elementos | `QueuedUlawInputStream.java` - LinkedBlockingQueue |
| **Timeout de Lectura Bedrock** | 180 segundos | HTTP/2 read timeout |

### Capacidad por Tipo de Instancia EC2

La siguiente tabla muestra la capacidad estimada de llamadas simultáneas por tipo de instancia:

| Tipo Instancia | vCPU | RAM | Llamadas Simultáneas<br/>(Estimado) | Llamadas Sostenidas<br/>(Baseline CPU) | Uso Recomendado |
|----------------|------|-----|-------------------------------------|----------------------------------------|-----------------|
| **t3.micro** | 2 | 1 GB | 3-5 | 1-2 | Desarrollo/POC únicamente |
| **t3.small** | 2 | 2 GB | 10-15 | 4-6 | Testing y demos |
| **t3.medium** | 2 | 4 GB | **20** ⚠️ | 8-12 | Producción pequeña (limitado por Nova) |
| **t3.large** | 2 | 8 GB | **20** ⚠️ | 16-20 | Producción pequeña (limitado por Nova) |
| **c5.large** | 2 | 4 GB | **20** ⚠️ | 20 | Producción (CPU dedicada, limitado por Nova) |
| **c5.xlarge** | 4 | 8 GB | **20** ⚠️ | 20 | Producción (limitado por Nova, no por recursos) |

⚠️ **Nota Crítica**: Independientemente del tamaño de instancia, el límite real es de **20 llamadas simultáneas por región** debido a las cuotas de Amazon Nova Sonic. Para superar este límite, se requiere arquitectura multi-región o aumentar cuotas con AWS.

### Señales de Saturación

Monitorea estos indicadores para detectar sobrecarga:

- **CPU Credits agotándose** (instancias T3) - visible en CloudWatch
- **Memoria swap aumentando** - indica falta de RAM
- **Calidad de audio degradándose** - dropouts, latencia, eco
- **Errores en logs de Bedrock** - timeouts, throttling (429 errors)
- **Errores RTP** - packet loss, jitter elevado
- **Sesiones rechazadas** - superando límite de 20 sesiones Nova

---

## Arquitecturas de Despliegue

### Arquitectura Actual: Single-Instance

La arquitectura actual despliega una sola instancia que maneja todas las llamadas.

```
┌─────────────────────────────────────────────────────────────┐
│                         AWS Cloud                            │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │                     VPC (10.0.0.0/16)                  │ │
│  │                                                        │ │
│  │  ┌──────────────────────────────────────────────────┐ │ │
│  │  │           Public Subnet (10.0.1.0/24)            │ │ │
│  │  │                                                  │ │ │
│  │  │  ┌────────────────────────────────────────────┐ │ │ │
│  │  │  │    EC2/ECS Instance (t3.medium)           │ │ │ │
│  │  │  │                                            │ │ │ │
│  │  │  │  ┌──────────────────────────────────────┐ │ │ │ │
│  │  │  │  │  Gateway VoIP Nova S2S               │ │ │ │ │
│  │  │  │  │  - SIP User Agent (Port 5060)        │ │ │ │ │
│  │  │  │  │  - RTP Media (Ports 10000-20000)     │ │ │ │ │
│  │  │  │  │  - Bedrock Client (Nova Sonic)       │ │ │ │ │
│  │  │  │  └──────────────────────────────────────┘ │ │ │ │
│  │  │  │                                            │ │ │ │
│  │  │  │  Max: 20 llamadas simultáneas             │ │ │ │
│  │  │  └────────────────────────────────────────────┘ │ │ │
│  │  │                                                  │ │ │
│  │  └──────────────────────────────────────────────────┘ │ │
│  │                                                        │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
│  Internet Gateway                                            │
│       │                                                      │
└───────┼──────────────────────────────────────────────────────┘
        │
        ▼
   Llamadas SIP
   desde Internet
```

**Pros:**
- Configuración simple
- Bajo costo operacional
- Fácil de debuggear
- Ideal para POC y desarrollo

**Contras:**
- Single Point of Failure (sin alta disponibilidad)
- Limitado a 20 llamadas por región
- Sin failover automático
- Downtime durante updates/mantenimiento

### Escalamiento Vertical

Aumentar el tamaño de la instancia para manejar más recursos.

**Cuándo usar:**
- Cuando las llamadas están cerca del límite de recursos de la instancia (CPU, RAM)
- Antes de alcanzar el límite de 20 sesiones Nova
- Para simplificar la arquitectura

**Proceso:**

1. **Para ECS:**
   ```bash
   # Editar cdk-ecs/lib/cdk-stack.ts
   # Cambiar instanceType de ec2.InstanceType.of()

   instanceType: ec2.InstanceType.of(
     ec2.InstanceClass.T3,
     ec2.InstanceSize.LARGE  // Cambiar de MEDIUM a LARGE
   ),

   # Re-desplegar
   cdk deploy
   ```

2. **Para EC2:**
   ```bash
   # Desde la consola EC2:
   # 1. Detener instancia
   # 2. Actions > Instance Settings > Change Instance Type
   # 3. Seleccionar nuevo tipo (ej: t3.large)
   # 4. Iniciar instancia
   ```

**Ruta de escalamiento sugerida:**
```
t3.micro (dev) → t3.small (testing) → t3.medium (prod pequeña)
    → c5.large (prod, CPU dedicada)
```

⚠️ **Recordatorio**: El escalamiento vertical no supera el límite de 20 sesiones Nova. Para más de 20 llamadas simultáneas, requiere multi-región.

### Escalamiento Horizontal: Multi-Instancia con Load Balancer

Para superar limitaciones de single-instance y mejorar disponibilidad.

#### Opción A: Network Load Balancer (NLB) + Auto Scaling Group

Arquitectura recomendada para distribución de carga SIP/RTP.

```
┌────────────────────────────────────────────────────────────────────────┐
│                            AWS Cloud                                    │
│                                                                         │
│  Internet Gateway                                                       │
│        │                                                                │
│        ▼                                                                │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │         Network Load Balancer (NLB)                              │  │
│  │         - Target Group: UDP 5060 (SIP)                           │  │
│  │         - Algorithm: Flow Hash (sticky sessions)                 │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│        │                    │                    │                      │
│   ┌────┴───────┐      ┌────┴───────┐      ┌────┴───────┐             │
│   ▼            ▼       ▼            ▼       ▼            ▼              │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐   │
│  │ Instance │ │ Instance │ │ Instance │ │ Instance │ │ Instance │   │
│  │    1     │ │    2     │ │    3     │ │    4     │ │    5     │   │
│  │          │ │          │ │          │ │          │ │          │   │
│  │ Gateway  │ │ Gateway  │ │ Gateway  │ │ Gateway  │ │ Gateway  │   │
│  │ (20 max) │ │ (20 max) │ │ (20 max) │ │ (20 max) │ │ (20 max) │   │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘   │
│  us-east-1a  us-east-1b   us-east-1c   us-east-1a   us-east-1b       │
│                                                                         │
│  Auto Scaling Group (min: 2, desired: 3, max: 10)                     │
│                                                                         │
│  Cada instancia → Max 20 llamadas por límite Nova                     │
│  Total teórico: 100 llamadas (5 instancias × 20 llamadas)             │
└────────────────────────────────────────────────────────────────────────┘
```

**Características:**
- Layer 4 (TCP/UDP) - ideal para SIP/RTP
- Preserva IP source del caller
- Sticky sessions basado en flow hash
- Alta performance, baja latencia

**⚠️ Limitación Importante**: Todas las instancias comparten el mismo límite de 20 sesiones Nova por región. Esta arquitectura **NO aumenta la capacidad total a más de 20 llamadas** a menos que se combine con multi-región.

**Configuración CDK Ejemplo (conceptual):**

```typescript
// En cdk-ecs/lib/cdk-stack.ts o nuevo stack

const nlb = new elbv2.NetworkLoadBalancer(this, 'VoipNLB', {
  vpc,
  internetFacing: true,
  crossZoneEnabled: true
});

const targetGroup = new elbv2.NetworkTargetGroup(this, 'VoipTargetGroup', {
  vpc,
  port: 5060,
  protocol: elbv2.Protocol.UDP,
  targetType: elbv2.TargetType.INSTANCE,
  healthCheck: {
    enabled: true,
    protocol: elbv2.Protocol.TCP,
    port: '5060'
  }
});

nlb.addListener('SIPListener', {
  port: 5060,
  protocol: elbv2.Protocol.UDP,
  defaultTargetGroups: [targetGroup]
});

const autoScalingGroup = new autoscaling.AutoScalingGroup(this, 'VoipASG', {
  vpc,
  instanceType: ec2.InstanceType.of(ec2.InstanceClass.T3, ec2.InstanceSize.MEDIUM),
  machineImage: ec2.MachineImage.latestAmazonLinux2(),
  minCapacity: 2,
  maxCapacity: 10,
  desiredCapacity: 3,
  healthCheck: autoscaling.HealthCheck.elb({ grace: cdk.Duration.minutes(5) })
});

autoScalingGroup.attachToNetworkTargetGroup(targetGroup);
```

**Pros:**
- Alta disponibilidad (multi-AZ)
- Failover automático
- Escalamiento basado en métricas

**Contras:**
- Complejidad aumentada
- Costo adicional (NLB + múltiples instancias)
- **NO supera límite de 20 sesiones Nova en single-region**
- SIP puede tener problemas con NAT/LB (requiere ajustes)

#### Opción B: Application Load Balancer (ALB) + ECS Fargate

Para arquitectura completamente serverless (no recomendado para SIP/RTP).

**Limitaciones:**
- ALB es Layer 7 (HTTP/HTTPS) - **no soporta UDP nativo**
- Requeriría proxy SIP→HTTP (complejidad adicional)
- No preserva características de VoIP nativas
- **No recomendado para esta aplicación**

### Estrategias de Auto Scaling

Si decides implementar auto scaling (con las limitaciones mencionadas), considera estas políticas:

#### Basado en CPU

```typescript
autoScalingGroup.scaleOnCpuUtilization('CpuScaling', {
  targetUtilizationPercent: 70,
  cooldown: cdk.Duration.minutes(3)
});
```

#### Basado en Métricas Personalizadas

```typescript
// Escalar basado en llamadas activas
const activeCallsMetric = new cloudwatch.Metric({
  namespace: 'VoIPGateway',
  metricName: 'ActiveCalls',
  statistic: 'Average'
});

autoScalingGroup.scaleOnMetric('CallsScaling', {
  metric: activeCallsMetric,
  scalingSteps: [
    { upper: 10, change: -1 },
    { lower: 15, change: +1 },
    { lower: 18, change: +2 }
  ]
});
```

**⚠️ Importante**: Dado que el límite de Nova es 20 sesiones por región, el auto scaling solo es útil cuando se combina con multi-región o cuando se solicita aumento de cuotas a AWS.

---

## Estrategias Multi-Región

Para superar el límite de 20 llamadas simultáneas, se requiere despliegue multi-región.

### Regiones Disponibles para Nova Sonic

| Región | Código AWS | Latencia Típica desde | Notas |
|--------|------------|----------------------|-------|
| **US East (N. Virginia)** | us-east-1 | América: 20-80ms | Región principal, mayor disponibilidad |
| **Europe (Stockholm)** | eu-north-1 | Europa: 10-50ms | Buena para tráfico europeo |
| **Asia Pacific (Tokyo)** | ap-northeast-1 | Asia: 10-80ms | Óptima para región Asia-Pacífico |

### Arquitectura Multi-Región: Activo-Pasivo

Configuración de failover básica.

```
┌──────────────────────────────────────────────────────────────────────┐
│                        Route 53 (DNS)                                 │
│              Health Checks + Failover Routing Policy                  │
└────────────────┬────────────────────────────┬────────────────────────┘
                 │ Primary                     │ Secondary (Failover)
                 ▼                             ▼
    ┌─────────────────────────┐   ┌─────────────────────────┐
    │   Región: us-east-1     │   │   Región: eu-north-1    │
    │   (Activa - 20 calls)   │   │   (Standby - 0 calls)   │
    │                         │   │                         │
    │  ┌──────────────────┐   │   │  ┌──────────────────┐   │
    │  │ Gateway Instance │   │   │  │ Gateway Instance │   │
    │  │   + Nova Sonic   │   │   │  │   + Nova Sonic   │   │
    │  └──────────────────┘   │   │  └──────────────────┘   │
    └─────────────────────────┘   └─────────────────────────┘
```

**Capacidad total**: 20 llamadas (solo primaria activa)
**Beneficio**: Alta disponibilidad (failover automático)
**Uso**: Cuando prioridad es uptime sobre capacidad

### Arquitectura Multi-Región: Activo-Activo

Distribución de carga geográfica.

```
┌──────────────────────────────────────────────────────────────────────┐
│                        Route 53 (DNS)                                 │
│                 Geolocation/Latency Routing Policy                    │
└───────┬─────────────────────┬─────────────────────┬──────────────────┘
        │ Americas             │ Europe               │ Asia
        ▼                      ▼                      ▼
┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
│  us-east-1       │  │  eu-north-1      │  │  ap-northeast-1  │
│  (20 calls max)  │  │  (20 calls max)  │  │  (20 calls max)  │
│                  │  │                  │  │                  │
│ ┌──────────────┐ │  │ ┌──────────────┐ │  │ ┌──────────────┐ │
│ │   Gateway    │ │  │ │   Gateway    │ │  │ │   Gateway    │ │
│ │ Nova Sonic   │ │  │ │ Nova Sonic   │ │  │ │ Nova Sonic   │ │
│ └──────────────┘ │  │ └──────────────┘ │  │ └──────────────┘ │
└──────────────────┘  └──────────────────┘  └──────────────────┘
```

**Capacidad total**: **60 llamadas simultáneas** (20 por región × 3 regiones)
**Beneficio**: Escalabilidad real + menor latencia por geo-proximidad
**Costo**: 3× infraestructura

### Consideraciones para Multi-Región

**Configuración Requerida:**

1. **Código actual tiene región hardcodeada** (`NovaStreamerFactory.java:54`):
   ```java
   // ACTUAL (hardcoded):
   BedrockRuntimeAsyncClient client = BedrockRuntimeAsyncClient.builder()
       .region(Region.US_EAST_1)  // ← Hardcoded
       .httpClientBuilder(nettyBuilder)
       .build();

   // MEJORADO (variable de entorno):
   String regionStr = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
   Region region = Region.of(regionStr);

   BedrockRuntimeAsyncClient client = BedrockRuntimeAsyncClient.builder()
       .region(region)
       .httpClientBuilder(nettyBuilder)
       .build();
   ```

2. **Route 53 Setup:**
   - Crear hosted zone para tu dominio SIP
   - Configurar health checks para cada región
   - Elegir routing policy (geolocation, latency-based, o failover)

3. **Configuración SIP:**
   - Cada región necesita su propio registro SIP
   - O usar un proxy SIP global que distribuya llamadas

**Limitaciones Conocidas:**
- Ver [`docs/OPERACIONES.md`](OPERACIONES.md) sección "Limitaciones Críticas" para lista completa
- Región hardcodeada es una limitación **CRÍTICA** para multi-región

---

## Configuración de Red

### Security Groups Requeridos

#### Reglas de Entrada (Inbound)

| Puerto/Rango | Protocolo | Source | Propósito |
|--------------|-----------|--------|-----------|
| 5060 | UDP | 0.0.0.0/0 | SIP Signaling |
| 10000-20000 | UDP | 0.0.0.0/0 | RTP Media (configurable) |

⚠️ **Importante**: El rango RTP 10000-20000 es el default. Puedes ajustarlo con:
- `MEDIA_PORT_BASE`: Puerto inicial (default: 10000)
- `MEDIA_PORT_COUNT`: Cantidad de puertos (default: 10000)

Ejemplo para reducir rango (si solo necesitas pocas llamadas):
```bash
MEDIA_PORT_BASE=10000
MEDIA_PORT_COUNT=100  # Solo 100 puertos = max 50 llamadas
```

#### Reglas de Salida (Outbound)

| Destino | Protocolo | Propósito |
|---------|-----------|-----------|
| 0.0.0.0/0 | All | Permite SIP, RTP, y llamadas a Bedrock API |

### VPC Considerations

**Subnets:**
- Usar **public subnets** para recibir tráfico SIP/RTP directo desde Internet
- Asignar **Elastic IP** para dirección pública estática (recomendado)

**Multi-AZ:**
- Desplegar instancias en múltiples Availability Zones para alta disponibilidad
- NLB automáticamente distribuye entre AZs

**VPC Endpoints:**
- Para ECS: VPC endpoints para ECR (com.amazonaws.region.ecr.dkr, com.amazonaws.region.ecr.api)
- Para logs: VPC endpoint para CloudWatch Logs (opcional, reduce costos de NAT)

### Direcciones IP y DNS

**Configuración de direcciones en variables de entorno:**

```bash
# Auto-detectadas por default, pero puedes forzarlas:
SIP_VIA_ADDR=<elastic-ip>      # Dirección para campo Via en SIP
MEDIA_ADDRESS=<private-ip>     # Dirección para RTP media
```

**Elastic IP recomendado:**
- Facilita configuración DNS
- IP estática para SIP registration
- No cambia si instancia se reemplaza

### Ejemplo de Security Group (CDK)

```typescript
const voipSecurityGroup = new ec2.SecurityGroup(this, 'VoipSG', {
  vpc,
  description: 'Security group for VoIP Gateway',
  allowAllOutbound: true
});

// SIP Signaling
voipSecurityGroup.addIngressRule(
  ec2.Peer.anyIpv4(),
  ec2.Port.udp(5060),
  'Allow SIP signaling'
);

// RTP Media
voipSecurityGroup.addIngressRule(
  ec2.Peer.anyIpv4(),
  ec2.Port.udpRange(10000, 20000),
  'Allow RTP media'
);
```

---

## Monitoreo de Capacidad

Para rastrear uso de capacidad en tiempo real, implementa estas métricas:

### CloudWatch Metrics

| Métrica | Namespace | Propósito |
|---------|-----------|-----------|
| ActiveConnections | Custom/VoIPGateway | Número de llamadas activas |
| CPUUtilization | AWS/EC2 | Uso de CPU |
| MemoryUtilization | CWAgent | Uso de memoria (requiere CloudWatch Agent) |
| NetworkIn/Out | AWS/EC2 | Tráfico de red |

### Alarmas Recomendadas

```typescript
// Alarma cuando te acercas al límite de Nova (18 de 20 sesiones)
new cloudwatch.Alarm(this, 'HighCallVolumeAlarm', {
  metric: activeCallsMetric,
  threshold: 18,
  evaluationPeriods: 2,
  alarmDescription: 'Approaching Nova Sonic session limit',
  actionsEnabled: true
});

// Alarma de CPU alta
new cloudwatch.Alarm(this, 'HighCPUAlarm', {
  metric: cpuMetric,
  threshold: 80,
  evaluationPeriods: 3,
  alarmDescription: 'High CPU utilization on VoIP Gateway'
});
```

Ver [`docs/OPERACIONES.md`](OPERACIONES.md) para guía completa de monitoreo.

---

## Resumen de Recomendaciones

| Escenario | Arquitectura Recomendada | Tipo de Instancia | Capacidad |
|-----------|-------------------------|-------------------|-----------|
| **Desarrollo/POC** | Single EC2 | t3.micro | 3-5 llamadas |
| **Testing/Demos** | Single EC2 | t3.small | 10-15 llamadas |
| **Producción < 20 llamadas** | Single EC2/ECS | t3.medium o c5.large | Hasta 20 llamadas |
| **Producción < 20 llamadas + HA** | ASG + NLB (single-region) | t3.medium (min 2) | Hasta 20 llamadas (con failover) |
| **Producción 20-60 llamadas** | Multi-región activo-activo | t3.medium (por región) | 20-60 llamadas (según # regiones) |
| **Producción > 60 llamadas** | Multi-región + solicitar aumento de cuotas a AWS | Evaluar según cuotas | Según cuotas aprobadas |

**Siguiente paso**: Ver [`docs/COSTOS-Y-PRECIOS.md`](COSTOS-Y-PRECIOS.md) para análisis de costos de cada arquitectura.
