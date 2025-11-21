# Operaciones y Mantenimiento

Este documento proporciona una guía operacional para el Gateway VoIP Nova S2S, incluyendo monitoreo, limitaciones arquitecturales conocidas, y troubleshooting.

## Tabla de Contenidos

- [Monitoreo](#monitoreo)
- [Limitaciones y Arquitectura Actual](#limitaciones-y-arquitectura-actual)
- [Troubleshooting](#troubleshooting)

---

## Monitoreo

### Métricas Clave de CloudWatch

Las siguientes métricas son esenciales para operación saludable del gateway.

#### Métricas de Infraestructura (AWS/EC2)

| Métrica | Namespace | Umbral de Alerta | Descripción |
|---------|-----------|------------------|-------------|
| **CPUUtilization** | AWS/EC2 | > 80% sostenido | Uso de CPU. Alerta cuando se acerca a saturación. |
| **NetworkIn** | AWS/EC2 | - | Tráfico de red entrante (RTP + SIP). |
| **NetworkOut** | AWS/EC2 | - | Tráfico de red saliente (RTP + Bedrock). |
| **StatusCheckFailed** | AWS/EC2 | > 0 | Fallas de health check de instancia. |

#### Métricas de Memoria (CloudWatch Agent - Requiere Configuración)

| Métrica | Namespace | Umbral de Alerta | Descripción |
|---------|-----------|------------------|-------------|
| **mem_used_percent** | CWAgent | > 85% | Porcentaje de memoria RAM utilizada. |
| **swap_used_percent** | CWAgent | > 10% | Uso de swap (indica falta de RAM). |

⚠️ **Nota**: Métricas de memoria requieren instalación del CloudWatch Agent. No están disponibles por default.

**Instalación de CloudWatch Agent**:
```bash
# En instancia EC2
wget https://s3.amazonaws.com/amazoncloudwatch-agent/amazon_linux/amd64/latest/amazon-cloudwatch-agent.rpm
sudo rpm -U ./amazon-cloudwatch-agent.rpm

# Configurar y iniciar
sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-config-wizard
sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl \
  -a fetch-config \
  -m ec2 \
  -s \
  -c file:/opt/aws/amazon-cloudwatch-agent/bin/config.json
```

#### Métricas Personalizadas (Recomendadas para Implementación Futura)

Actualmente **NO implementadas** en el código. Recomendamos agregar:

| Métrica | Namespace Sugerido | Propósito |
|---------|-------------------|-----------|
| **ActiveCalls** | Custom/VoIPGateway | Número de llamadas activas en tiempo real |
| **CallDuration** | Custom/VoIPGateway | Duración promedio de llamadas |
| **CallSuccessRate** | Custom/VoIPGateway | % de llamadas completadas exitosamente |
| **BedrockErrors** | Custom/VoIPGateway | Errores de API de Bedrock (throttling, timeouts) |
| **RTPPacketLoss** | Custom/VoIPGateway | Pérdida de paquetes RTP (calidad de audio) |
| **SIPRegistrationStatus** | Custom/VoIPGateway | Estado de registro SIP (1=registered, 0=failed) |

**Ejemplo de implementación** (pseudocódigo para agregar en el futuro):

```java
// En NovaStreamerFactory.createMediaStreamer()
CloudWatchAsyncClient cwClient = CloudWatchAsyncClient.create();

// Al iniciar una llamada
cwClient.putMetricData(PutMetricDataRequest.builder()
    .namespace("Custom/VoIPGateway")
    .metricData(MetricDatum.builder()
        .metricName("ActiveCalls")
        .value(1.0)
        .unit(StandardUnit.COUNT)
        .build())
    .build());
```

### Dashboards Recomendados

#### Dashboard Básico (CloudWatch Console)

Crea un dashboard con las siguientes widgets:

**1. CPU y Memoria**
```json
{
  "type": "metric",
  "properties": {
    "metrics": [
      ["AWS/EC2", "CPUUtilization", {"stat": "Average"}],
      ["CWAgent", "mem_used_percent", {"stat": "Average"}]
    ],
    "period": 300,
    "stat": "Average",
    "region": "us-east-1",
    "title": "CPU y Memoria"
  }
}
```

**2. Tráfico de Red**
```json
{
  "type": "metric",
  "properties": {
    "metrics": [
      ["AWS/EC2", "NetworkIn", {"stat": "Sum"}],
      ["AWS/EC2", "NetworkOut", {"stat": "Sum"}]
    ],
    "period": 300,
    "stat": "Sum",
    "region": "us-east-1",
    "title": "Tráfico de Red (Bytes)"
  }
}
```

**3. Logs de Errores** (usando Logs Insights)
```sql
fields @timestamp, @message
| filter @message like /ERROR/ or @message like /Exception/
| sort @timestamp desc
| limit 20
```

### Señales de Saturación

Monitorea estos indicadores para detectar cuando el sistema está alcanzando límites:

| Señal | Métrica | Umbral Crítico | Acción Recomendada |
|-------|---------|---------------|-------------------|
| **CPU saturada** | CPUUtilization | > 80% sostenido 5+ min | Escalar verticalmente (instancia más grande) |
| **Memoria agotándose** | mem_used_percent | > 90% | Escalar verticalmente o reducir llamadas |
| **Swap activo** | swap_used_percent | > 0% | Aumentar RAM (señal de falta de memoria) |
| **CPU Credits agotados** (T3) | CPUCreditBalance | < 50 credits | Cambiar a instancia no-burstable (C5) o reducir carga |
| **Errores de Bedrock** | Logs | Throttling (429) | Alcanzaste límite de Nova (20 sesiones o TPM) |
| **Packet loss RTP** | Logs | > 1% loss | Problema de red o CPU saturada |
| **Sesiones Nova rechazadas** | Logs | "Session limit" | Alcanzaste límite de 20 sesiones simultáneas |

### Alarmas Recomendadas

#### CloudWatch Alarms (CDK)

```typescript
// Alarma: CPU Alta
new cloudwatch.Alarm(this, 'HighCPUAlarm', {
  metric: instance.metricCPUUtilization(),
  threshold: 80,
  evaluationPeriods: 3,
  datapointsToAlarm: 2,
  comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
  treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
  alarmDescription: 'Alerta cuando CPU > 80% por 3 períodos (6 minutos)',
  actionsEnabled: true
});

// Alarma: StatusCheckFailed
new cloudwatch.Alarm(this, 'StatusCheckFailedAlarm', {
  metric: instance.metricStatusCheckFailed(),
  threshold: 1,
  evaluationPeriods: 2,
  comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
  alarmDescription: 'Alerta cuando la instancia falla health checks',
  actionsEnabled: true
});

// Alarma: Presupuesto (Budget)
new budgets.CfnBudget(this, 'CostAlarm', {
  budget: {
    budgetName: 'VoIPGatewayMonthly',
    budgetLimit: { amount: 1500, unit: 'USD' },
    timeUnit: 'MONTHLY',
    budgetType: 'COST'
  },
  notificationsWithSubscribers: [{
    notification: {
      notificationType: 'ACTUAL',
      comparisonOperator: 'GREATER_THAN',
      threshold: 80
    },
    subscribers: [{
      subscriptionType: 'EMAIL',
      address: 'devops@example.com'
    }]
  }]
});
```

### Logs Importantes

#### Ubicación de Logs

**EC2**:
- Application logs: `/var/log/voip-gateway.log` (si configurado)
- System logs: `/var/log/messages`, `/var/log/syslog`
- stdout/stderr: Capturados por systemd o script de inicio

**ECS**:
- CloudWatch Logs: `/aws/ecs/voip-gateway`
- Log group configurado en task definition

#### Patrones de Logs Importantes

**1. Registro SIP Exitoso**
```
Registering with sip:SERVER...
Registration successful (200 OK)
Keep-alive started
```

**2. Llamada Entrante**
```
Incoming call from: sip:caller@domain.com
Creating Nova streamer...
Sending session start event...
Input observer ready
```

**3. Errores de Nova Sonic**
```
ERROR: Bedrock error: com.amazonaws.SdkClientException: Unable to execute HTTP request
ERROR: Session limit exceeded (20 concurrent sessions)
WARN: Throttling detected (429 TooManyRequestsException)
```

**4. Errores RTP**
```
ERROR: RTP packet loss detected: 5.2%
WARN: RTP jitter high: 150ms
ERROR: Failed to bind RTP port 10234
```

#### CloudWatch Logs Insights Queries Útiles

**Encontrar errores recientes**:
```sql
fields @timestamp, @message
| filter @message like /ERROR/ or @message like /Exception/
| sort @timestamp desc
| limit 50
```

**Contar llamadas por hora**:
```sql
fields @timestamp
| filter @message like "Incoming call"
| stats count() as calls by bin(1h)
```

**Detectar throttling de Bedrock**:
```sql
fields @timestamp, @message
| filter @message like "429" or @message like "TooManyRequestsException"
| sort @timestamp desc
```

**Duración de sesiones**:
```sql
fields @timestamp, @message
| filter @message like "Session ended"
| parse @message /duration: (?<duration>\d+)/
| stats avg(duration) as avg_duration_ms, max(duration) as max_duration_ms
```

---

## Limitaciones y Arquitectura Actual

Esta sección documenta limitaciones técnicas conocidas del código actual, priorizadas por severidad.

### Limitaciones Críticas

Estas limitaciones **bloquean producción a escala** o introducen **single points of failure**.

#### 1. Región de Bedrock Hardcodeada

**Ubicación**: `NovaStreamerFactory.java:54`

```java
BedrockRuntimeAsyncClient client = BedrockRuntimeAsyncClient.builder()
    .region(Region.US_EAST_1)  // ← Hardcoded, no configurable
    .httpClientBuilder(nettyBuilder)
    .build();
```

**Impacto**:
- ❌ Imposible desplegar en otras regiones (Stockholm, Tokyo)
- ❌ No se puede implementar multi-región para superar límite de 20 sesiones
- ❌ No se puede optimizar latencia para usuarios en otras geografías

**Severidad**: 🔴 **CRÍTICA**

**Esfuerzo de Solución**: 🟢 **Bajo** (2-4 horas)

**Solución Propuesta**:
```java
// Leer región de variable de entorno o usar default
String regionStr = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
Region region = Region.of(regionStr);

BedrockRuntimeAsyncClient client = BedrockRuntimeAsyncClient.builder()
    .region(region)
    .httpClientBuilder(nettyBuilder)
    .build();
```

#### 2. Sin Alta Disponibilidad (Single Instance)

**Descripción**: Arquitectura actual despliega solo 1 instancia. Si falla, el servicio cae completamente.

**Impacto**:
- ❌ Downtime durante mantenimiento (updates, patches)
- ❌ Downtime si instancia falla (hardware, AZ failure)
- ❌ No hay failover automático
- ❌ No cumple SLA de producción típicos (99.9%+)

**Severidad**: 🔴 **CRÍTICA** (para producción)

**Esfuerzo de Solución**: 🟡 **Medio** (1-2 días)

**Solución Propuesta**:
- Implementar Auto Scaling Group con min 2 instancias
- Agregar Network Load Balancer para distribuir tráfico SIP
- Configurar health checks
- Ver [`docs/INFRAESTRUCTURA-Y-ESCALABILIDAD.md`](INFRAESTRUCTURA-Y-ESCALABILIDAD.md) sección "Escalamiento Horizontal"

#### 3. Sin Health Checks Automáticos

**Descripción**: No hay health checks implementados que permitan detectar si el servicio está funcionando.

**Impacto**:
- ❌ Load balancers no pueden detectar instancias unhealthy
- ❌ Auto scaling no puede reemplazar instancias fallidas
- ❌ Monitoreo externo limitado

**Severidad**: 🔴 **CRÍTICA** (para HA)

**Esfuerzo de Solución**: 🟡 **Medio** (4-8 horas)

**Solución Propuesta**:
```java
// Agregar endpoint HTTP simple para health check
@RestController
public class HealthCheckController {
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        // Verificar que SIP está registrado
        // Verificar que Bedrock client está funcional
        if (isHealthy()) {
            return ResponseEntity.ok("OK");
        } else {
            return ResponseEntity.status(503).body("Unhealthy");
        }
    }
}
```

### Limitaciones Altas

Estas limitaciones **afectan operación** y escalabilidad, pero tienen workarounds.

#### 4. Límite de 20 Sesiones Nova Sonic por Cuenta/Región

**Descripción**: Amazon Nova Sonic limita a 20 sesiones concurrentes por cuenta AWS por región.

**Impacto**:
- ⚠️ Máximo 20 llamadas simultáneas por región
- ⚠️ Llamadas adicionales son rechazadas
- ⚠️ No se puede escalar más allá sin multi-región

**Severidad**: 🟠 **ALTA**

**Esfuerzo de Solución**: 🟡 **Medio** (multi-región) o contactar AWS Sales

**Soluciones**:
1. **Multi-región**: Desplegar en us-east-1, eu-north-1, ap-northeast-1 (3× capacidad)
2. **Solicitar aumento de cuota**: Contactar AWS Support (puede requerir semanas)
3. **Queue management**: Implementar cola de espera cuando límite se alcanza

**Estado**: Limitación de AWS, no del código.

#### 5. Sin Failover Multi-Región

**Descripción**: Incluso si se despliegan múltiples regiones, no hay lógica de failover automático.

**Impacto**:
- ⚠️ Si región primaria falla, no hay fallback automático
- ⚠️ Requiere intervención manual (cambio de DNS)

**Severidad**: 🟠 **ALTA** (para multi-región)

**Esfuerzo de Solución**: 🟡 **Medio** (1-2 días)

**Solución Propuesta**:
- Implementar Route 53 health checks
- Configurar failover routing policy
- Ejemplo en [`docs/INFRAESTRUCTURA-Y-ESCALABILIDAD.md`](INFRAESTRUCTURA-Y-ESCALABILIDAD.md)

#### 6. Sin Queue Management para Llamadas

**Descripción**: Cuando se alcanza el límite de capacidad (20 sesiones), nuevas llamadas son rechazadas inmediatamente.

**Impacto**:
- ⚠️ Experiencia de usuario pobre (busy signal)
- ⚠️ No hay opción de "esperar en cola"

**Severidad**: 🟠 **ALTA** (para UX)

**Esfuerzo de Solución**: 🔴 **Alto** (1-2 semanas)

**Solución Propuesta**:
- Implementar cola de espera en SIP (usando SIP queue/park)
- Reproducir música de espera
- Informar posición en cola
- Timeout y callback si espera es muy larga

### Limitaciones Medias

Mejoras deseables que mejoran operación pero no son bloqueantes.

#### 7. Sin Métricas Custom de CloudWatch

**Descripción**: No hay métricas personalizadas publicadas a CloudWatch (ActiveCalls, CallDuration, etc.).

**Impacto**:
- ⚠️ Visibilidad limitada de métricas de negocio
- ⚠️ No se pueden crear alarmas basadas en llamadas activas
- ⚠️ Auto scaling no puede basarse en métricas reales de uso

**Severidad**: 🟡 **MEDIA**

**Esfuerzo de Solución**: 🟡 **Medio** (1-2 días)

**Solución**: Ver sección "Métricas Personalizadas" arriba.

#### 8. Sin Circuit Breaker para Bedrock

**Descripción**: Si Bedrock API comienza a fallar o responder lento, el sistema continúa intentando llamadas.

**Impacto**:
- ⚠️ Puede causar backlog de llamadas fallidas
- ⚠️ Costos innecesarios de reintentos
- ⚠️ Experiencia degradada prolongada

**Severidad**: 🟡 **MEDIA**

**Esfuerzo de Solución**: 🟡 **Medio** (4-8 horas)

**Solución Propuesta**:
```java
// Usar Resilience4j o similar
CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("bedrock");

circuitBreaker.executeSupplier(() -> {
    return bedrockClient.invokeModelWithBidirectionalStream(...);
});
```

#### 9. Logs No Estructurados

**Descripción**: Logs actuales son texto plano, no JSON estructurado.

**Impacto**:
- ⚠️ Más difícil parsear con CloudWatch Logs Insights
- ⚠️ No hay campos indexados (call_id, session_id, etc.)
- ⚠️ Queries de logs más lentas

**Severidad**: 🟡 **MEDIA**

**Esfuerzo de Solución**: 🟢 **Bajo** (4-6 horas)

**Solución Propuesta**:
```java
// Usar Logback con layout JSON
<encoder class="net.logstash.logback.encoder.LogstashEncoder">
    <includeMdc>true</includeMdc>
</encoder>

// En código, agregar contexto
MDC.put("call_id", callId);
MDC.put("session_id", sessionId);
log.info("Call started");
```

#### 10. Sin Configuración de Timeouts Personalizables

**Descripción**: Timeouts están hardcodeados (ej: 180s read timeout en Bedrock).

**Impacto**:
- ⚠️ No se pueden ajustar timeouts según necesidades
- ⚠️ 180s puede ser demasiado largo para algunos casos de uso

**Severidad**: 🟡 **MEDIA**

**Esfuerzo de Solución**: 🟢 **Bajo** (2-4 horas)

**Solución**:
```java
String timeoutStr = System.getenv().getOrDefault("BEDROCK_READ_TIMEOUT", "180");
Duration timeout = Duration.ofSeconds(Long.parseLong(timeoutStr));

NettyNioAsyncHttpClient.builder()
    .readTimeout(timeout)
    ...
```

### Resumen de Limitaciones

| # | Limitación | Severidad | Esfuerzo | Prioridad |
|---|-----------|-----------|----------|-----------|
| 1 | Región hardcodeada | 🔴 Crítica | 🟢 Bajo | **P0** |
| 2 | Sin alta disponibilidad | 🔴 Crítica | 🟡 Medio | **P0** |
| 3 | Sin health checks | 🔴 Crítica | 🟡 Medio | **P0** |
| 4 | Límite 20 sesiones Nova | 🟠 Alta | 🟡 Medio | **P1** |
| 5 | Sin failover multi-región | 🟠 Alta | 🟡 Medio | **P1** |
| 6 | Sin queue management | 🟠 Alta | 🔴 Alto | **P2** |
| 7 | Sin métricas custom | 🟡 Media | 🟡 Medio | **P2** |
| 8 | Sin circuit breaker | 🟡 Media | 🟡 Medio | **P3** |
| 9 | Logs no estructurados | 🟡 Media | 🟢 Bajo | **P3** |
| 10 | Timeouts hardcodeados | 🟡 Media | 🟢 Bajo | **P3** |

**Recomendación**: Resolver limitaciones **P0** antes de ir a producción.

---

## Troubleshooting

Guía de problemas comunes y sus soluciones.

### Problema 1: SIP Registration Failed (403 Forbidden)

**Síntomas**:
```
ERROR: Registration failed: 403 Forbidden
```

**Causas posibles**:
1. Credenciales incorrectas (`AUTH_USER`, `AUTH_PASSWORD`)
2. Realm incorrecto (`AUTH_REALM`)
3. IP bloqueada por servidor SIP

**Solución**:
```bash
# Verificar variables de entorno
echo $AUTH_USER
echo $AUTH_REALM

# Verificar que SIP_SERVER es alcanzable
nc -vz $SIP_SERVER 5060

# Revisar logs del servidor SIP para más detalles
```

### Problema 2: Audio One-Way (solo puedo escuchar o solo puedo hablar)

**Síntomas**:
- Puedo escuchar a Nova pero Nova no me escucha, o viceversa

**Causas posibles**:
1. Firewall bloqueando RTP en alguna dirección
2. Problema de NAT/routing
3. `MEDIA_ADDRESS` configurada incorrectamente

**Solución**:
```bash
# Verificar security group permite UDP 10000-20000 inbound/outbound
aws ec2 describe-security-groups --group-ids sg-xxxxx

# Verificar que MEDIA_ADDRESS coincide con IP pública
curl http://checkip.amazonaws.com
echo $MEDIA_ADDRESS

# Verificar con tcpdump que RTP está fluyendo
sudo tcpdump -i any -n udp port 10000-20000
```

### Problema 3: "Session limit exceeded" en Logs

**Síntomas**:
```
ERROR: Bedrock error: Session limit exceeded (20 concurrent sessions)
```

**Causas**:
- Alcanzaste el límite de 20 sesiones simultáneas de Nova Sonic

**Solución**:
```bash
# Verificar cuántas llamadas están activas
# (requiere implementar métrica custom - no disponible actualmente)

# Soluciones:
# 1. Esperar a que llamadas terminen
# 2. Implementar multi-región
# 3. Solicitar aumento de cuota a AWS Support
```

### Problema 4: Alta Latencia en Audio

**Síntomas**:
- Delay notable entre hablar y recibir respuesta (> 2-3 segundos)

**Causas posibles**:
1. Latencia de red hacia Bedrock (región lejana)
2. CPU saturada (transcoding lento)
3. Jitter alto en RTP

**Solución**:
```bash
# Verificar latencia hacia Bedrock
ping bedrock-runtime.us-east-1.amazonaws.com

# Verificar CPU
top -bn1 | grep java

# Si CPU > 80%, escalar instancia
# Si latencia de red alta, considerar región más cercana
```

### Problema 5: Calls Dropping Randomly

**Síntomas**:
- Llamadas se cortan inesperadamente

**Causas posibles**:
1. SIP keepalive fallando
2. Timeout de Bedrock (180s default)
3. Instancia quedándose sin memoria
4. Problema de red

**Solución**:
```bash
# Revisar logs en momento de drop
grep "Session ended" /var/log/voip-gateway.log
grep "Exception" /var/log/voip-gateway.log

# Verificar memoria
free -m
# Si swap > 0, hay falta de memoria

# Verificar SIP keepalive
# Buscar en logs: "Keep-alive started"

# Ajustar keepalive si es necesario
export SIP_KEEPALIVE_TIME=30000  # 30 segundos en lugar de 60
```

### Problema 6: Cannot Build Maven Project (mjSIP dependency)

**Síntomas**:
```
[ERROR] Failed to execute goal on project s2s-voip-gateway:
Could not resolve dependencies for project:
Failed to collect dependencies at org.mjsip:mjsip:jar:2.0.5
```

**Causa**:
- Maven settings.xml no configurado con credenciales de GitHub

**Solución**:
```bash
# Crear ~/.m2/settings.xml
cat > ~/.m2/settings.xml <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0
          http://maven.apache.org/xsd/settings-1.0.0.xsd">
    <servers>
        <server>
            <id>github</id>
            <username>YOUR_GITHUB_USERNAME</username>
            <password>YOUR_GITHUB_TOKEN</password>
        </server>
    </servers>
</settings>
EOF

# Crear token en: https://github.com/settings/tokens
# Scope requerido: read:packages
```

### Problema 7: Bedrock Throttling (429 TooManyRequestsException)

**Síntomas**:
```
ERROR: TooManyRequestsException: Rate exceeded
```

**Causa**:
- Excediste límites de TPM (2M tokens/min) o RPM (2K requests/min)

**Solución**:
```bash
# Calcular tokens por minuto actual
# Con 20 llamadas simultáneas de 5 min promedio:
# ~20 × 4,500 tokens/min × 2 (input+output) = ~180K tokens/min
# Esto está muy por debajo de 2M, así que probablemente es RPM

# Verificar logs para patrones inusuales
# ¿Hay bucle de reconexiones?
# ¿Muchas llamadas cortas en rápida sucesión?

# Solución temporal: implementar rate limiting en código
# Solución permanente: solicitar aumento de cuota
```

### Problema 8: Poor Audio Quality (Choppy, Robotic)

**Síntomas**:
- Audio entrecortado, robótico, o con artifacts

**Causas posibles**:
1. Packet loss en RTP
2. CPU saturada (transcoding lento)
3. Problema de codec mismatch

**Solución**:
```bash
# Verificar packet loss
# (requiere logging adicional - no implementado actualmente)

# Verificar CPU
top -bn1 | grep java
# Si > 80%, escalar instancia

# Verificar codec en uso
# Debe ser PCMU (μ-law) codec 0
# Ver en logs SIP: "RTP/AVP 0"

# Verificar jitter en RTP
sudo tcpdump -i any -vvv -s 0 udp port 10000-20000
```

### Problema 9: High CloudWatch Costs

**Síntomas**:
- Factura de CloudWatch Logs > $50/mes

**Causa**:
- Logs muy verbose (DEBUG_SIP=true, DEBUG_AUDIO_OUTPUT=true)
- Retención de logs en INFINITE

**Solución**:
```bash
# Deshabilitar logs debug en producción
export DEBUG_SIP=false
export DEBUG_AUDIO_OUTPUT=false

# Configurar retención de logs
aws logs put-retention-policy \
    --log-group-name /aws/ecs/voip-gateway \
    --retention-in-days 7

# O en CDK:
logGroup.retention = logs.RetentionDays.ONE_WEEK;
```

### Problema 10: Instance Out of Memory (OOM)

**Síntomas**:
```
java.lang.OutOfMemoryError: Java heap space
```

**Causa**:
- JVM heap size demasiado pequeño para número de llamadas
- Memory leak

**Solución**:
```bash
# Aumentar heap size
java -Xmx700m -Xms700m -jar s2s-voip-gateway.jar

# O escalar instancia (t3.micro → t3.small)

# Si persiste, puede ser memory leak
# Obtener heap dump para análisis:
jmap -dump:format=b,file=heap.bin <PID>
```

### Comandos Útiles para Troubleshooting

```bash
# Ver procesos Java
ps aux | grep java

# Ver uso de memoria
free -m
top -bn1 | head -20

# Ver conexiones SIP/RTP
netstat -an | grep -E "5060|10000"

# Ver logs en tiempo real
tail -f /var/log/voip-gateway.log

# O en ECS:
aws logs tail /aws/ecs/voip-gateway --follow

# Verificar registro SIP
# Buscar: "Registration successful (200 OK)"

# Test de conectividad SIP
nc -vuz <SIP_SERVER> 5060

# Ver tráfico SIP
sudo tcpdump -i any -n -s 0 -v port 5060

# Ver tráfico RTP
sudo tcpdump -i any -n -s 0 udp portrange 10000-20000
```

---

## Checklist Pre-Producción

Antes de ir a producción, asegúrate de completar:

- [ ] Resolver limitaciones P0 (región hardcodeada, HA, health checks)
- [ ] Implementar alarmas de CloudWatch para CPU, memoria, costos
- [ ] Configurar retención de logs (no INFINITE)
- [ ] Deshabilitar logs debug (`DEBUG_SIP=false`)
- [ ] Configurar backups de configuración
- [ ] Documentar runbook específico de tu deployment
- [ ] Probar failover (si multi-instancia)
- [ ] Prueba de carga para validar capacidad esperada
- [ ] Implementar monitoreo externo (ej: Pingdom, StatusPage)
- [ ] Configurar alertas vía SNS/email/Slack
- [ ] Revisar permisos IAM (principio de mínimo privilegio)
- [ ] Configurar AWS Budgets para prevenir sobrecostos

---

## Recursos Adicionales

- **AWS Bedrock Limits**: https://docs.aws.amazon.com/bedrock/latest/userguide/quotas.html
- **Amazon Nova Sonic Docs**: https://docs.aws.amazon.com/nova/latest/userguide/
- **CloudWatch Agent Setup**: https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/Install-CloudWatch-Agent.html
- **SIP Troubleshooting**: https://tools.ietf.org/html/rfc3261

**Siguiente paso**: Ver [`docs/INFRAESTRUCTURA-Y-ESCALABILIDAD.md`](INFRAESTRUCTURA-Y-ESCALABILIDAD.md) para planificar arquitectura de producción.
