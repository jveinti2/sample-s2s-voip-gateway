# Costos y Precios

Este documento proporciona un análisis detallado de los costos asociados con el Gateway VoIP Nova S2S, incluyendo precios de Amazon Nova Sonic, infraestructura AWS, y análisis por diferentes escenarios de uso.

## Tabla de Contenidos

- [Pricing de Amazon Nova Sonic](#pricing-de-amazon-nova-sonic)
- [Análisis de Costos por Escenario](#análisis-de-costos-por-escenario)
- [Comparativa por Región](#comparativa-por-región)
- [Comparativa por Tipo de Instancia](#comparativa-por-tipo-de-instancia)
- [Fórmulas de Cálculo](#fórmulas-de-cálculo)
- [Optimización de Costos](#optimización-de-costos)

---

## Pricing de Amazon Nova Sonic

Amazon Nova Sonic utiliza un modelo de pricing basado en tokens procesados.

### Tokens de Audio

Los tokens de audio son la **unidad primaria de costo** para conversaciones de voz.

| Tipo | Precio por 1,000 tokens | Notas |
|------|------------------------|-------|
| **Audio Input** | $0.0034 | Audio que envía el usuario (caller) a Nova Sonic |
| **Audio Output** | $0.0136 | Audio que genera Nova Sonic de vuelta al usuario |

**Relación tokens/tiempo:**
- Aproximadamente **150 tokens = 1 segundo de audio**
- 1 minuto de conversación ≈ 9,000 tokens (input + output)

### Tokens de Texto

Los tokens de texto se usan en casos específicos: transcripciones, historial de conversación, y tool calls.

| Tipo | Precio por 1,000 tokens | Notas |
|------|------------------------|-------|
| **Text Input** | $0.00006 | System prompts, tool results, historial |
| **Text Output** | $0.00024 | Transcripciones, respuestas de herramientas |

**Uso típico de tokens de texto:**
- System prompt inicial: ~100-500 tokens (una vez por sesión)
- Tool invocations: ~50-200 tokens por llamada de herramienta
- Transcripciones: variables según configuración

### Comparativa con Competidores

| Servicio | Costo estimado<br/>10 hrs conversación/día | Notas |
|----------|-------------------------------------------|-------|
| **Amazon Nova Sonic** | ~$7/día | Basado en pricing actual |
| OpenAI GPT-4o | ~$35/día | ~80% más costoso que Nova |
| Google Cloud STT + TTS | ~$12-18/día | Requiere integración separada |

**Ventaja de Nova Sonic**: ~80% más económico que alternativas de OpenAI.

---

## Análisis de Costos por Escenario

Los siguientes escenarios asumen:
- **Duración promedio por llamada**: 5 minutos
- **Distribución**: 50% input (caller habla) / 50% output (Nova habla)
- **Región**: us-east-1 (precios pueden variar levemente por región)
- **Tokens de texto**: ~300 tokens por sesión (system prompt + tools)

### Escenario 1: Desarrollo/POC (5 llamadas/día)

**Volumen:**
- 5 llamadas/día × 5 minutos = 25 minutos/día
- ~25 días laborables/mes = 625 minutos/mes

| Componente | Cálculo | Costo Mensual |
|------------|---------|---------------|
| **Nova Sonic - Audio Input** | 625 min × 4,500 tokens/min × $0.0034/1K | **$9.56** |
| **Nova Sonic - Audio Output** | 625 min × 4,500 tokens/min × $0.0136/1K | **$38.25** |
| **Nova Sonic - Text** | 5 calls/day × 25 days × 300 tokens × $0.00024/1K | **$0.09** |
| **EC2 t3.micro** | 730 hrs × $0.0104/hr (us-east-1) | **$7.59** |
| **Data Transfer** | ~10 GB outbound × $0.09/GB | **$0.90** |
| **CloudWatch Logs** | ~5 GB × $0.50/GB | **$2.50** |
| | **TOTAL MENSUAL** | **~$59** |

**Costo por llamada**: $59 / 125 llamadas = **$0.47/llamada**

### Escenario 2: Testing (50 llamadas/día)

**Volumen:**
- 50 llamadas/día × 5 minutos = 250 minutos/día
- ~25 días laborables/mes = 6,250 minutos/mes

| Componente | Cálculo | Costo Mensual |
|------------|---------|---------------|
| **Nova Sonic - Audio Input** | 6,250 min × 4,500 tokens/min × $0.0034/1K | **$95.63** |
| **Nova Sonic - Audio Output** | 6,250 min × 4,500 tokens/min × $0.0136/1K | **$382.50** |
| **Nova Sonic - Text** | 50 calls/day × 25 days × 300 tokens × $0.00024/1K | **$0.90** |
| **EC2 t3.small** | 730 hrs × $0.0208/hr (us-east-1) | **$15.18** |
| **Data Transfer** | ~100 GB outbound × $0.09/GB | **$9.00** |
| **CloudWatch Logs** | ~20 GB × $0.50/GB | **$10.00** |
| | **TOTAL MENSUAL** | **~$513** |

**Costo por llamada**: $513 / 1,250 llamadas = **$0.41/llamada**

### Escenario 3: Producción Pequeña (100 llamadas/día, < 20 simultáneas)

**Volumen:**
- 100 llamadas/día × 5 minutos = 500 minutos/día
- ~25 días laborables/mes = 12,500 minutos/mes

| Componente | Cálculo | Costo Mensual |
|------------|---------|---------------|
| **Nova Sonic - Audio Input** | 12,500 min × 4,500 tokens/min × $0.0034/1K | **$191.25** |
| **Nova Sonic - Audio Output** | 12,500 min × 4,500 tokens/min × $0.0136/1K | **$765.00** |
| **Nova Sonic - Text** | 100 calls/day × 25 days × 300 tokens × $0.00024/1K | **$1.80** |
| **EC2 t3.medium** | 730 hrs × $0.0416/hr (us-east-1) | **$30.37** |
| **Data Transfer** | ~200 GB outbound × $0.09/GB | **$18.00** |
| **CloudWatch Logs** | ~40 GB × $0.50/GB | **$20.00** |
| | **TOTAL MENSUAL** | **~$1,026** |

**Costo por llamada**: $1,026 / 2,500 llamadas = **$0.41/llamada**

### Escenario 4: Producción Media con HA (200 llamadas/día, multi-AZ)

**Volumen:**
- 200 llamadas/día × 5 minutos = 1,000 minutos/día
- ~25 días laborables/mes = 25,000 minutos/mes

**Arquitectura**: 2 instancias t3.medium + Network Load Balancer (Alta disponibilidad)

| Componente | Cálculo | Costo Mensual |
|------------|---------|---------------|
| **Nova Sonic - Audio Input** | 25,000 min × 4,500 tokens/min × $0.0034/1K | **$382.50** |
| **Nova Sonic - Audio Output** | 25,000 min × 4,500 tokens/min × $0.0136/1K | **$1,530.00** |
| **Nova Sonic - Text** | 200 calls/day × 25 days × 300 tokens × $0.00024/1K | **$3.60** |
| **EC2 t3.medium × 2** | 2 × 730 hrs × $0.0416/hr | **$60.74** |
| **Network Load Balancer** | 730 hrs × $0.0225/hr + LCU charges ~$15 | **$31.43** |
| **Data Transfer** | ~400 GB outbound × $0.09/GB | **$36.00** |
| **CloudWatch Logs** | ~80 GB × $0.50/GB | **$40.00** |
| | **TOTAL MENSUAL** | **~$2,084** |

**Costo por llamada**: $2,084 / 5,000 llamadas = **$0.42/llamada**

⚠️ **Nota**: Recuerda que el límite de Nova es 20 sesiones simultáneas por región. Para 200 llamadas/día distribuidas, raramente alcanzarás 20 simultáneas, pero si tienes picos, necesitarás multi-región.

### Escenario 5: Producción Alta - Multi-Región (500 llamadas/día)

**Volumen:**
- 500 llamadas/día × 5 minutos = 2,500 minutos/día
- ~25 días laborables/mes = 62,500 minutos/mes

**Arquitectura**: 3 regiones (us-east-1, eu-north-1, ap-northeast-1), 1 instancia t3.medium por región

| Componente | Cálculo | Costo Mensual |
|------------|---------|---------------|
| **Nova Sonic - Audio Input** | 62,500 min × 4,500 tokens/min × $0.0034/1K | **$956.25** |
| **Nova Sonic - Audio Output** | 62,500 min × 4,500 tokens/min × $0.0136/1K | **$3,825.00** |
| **Nova Sonic - Text** | 500 calls/day × 25 days × 300 tokens × $0.00024/1K | **$9.00** |
| **EC2 t3.medium × 3 regiones** | 3 × 730 hrs × $0.0416/hr (promedio) | **$91.10** |
| **Route 53 Hosted Zone** | 1 zone × $0.50/month + queries | **$0.50** |
| **Route 53 Health Checks** | 3 checks × $0.50/check | **$1.50** |
| **Data Transfer** | ~1,000 GB outbound × $0.09/GB (promedio) | **$90.00** |
| **CloudWatch Logs** | ~200 GB × $0.50/GB | **$100.00** |
| | **TOTAL MENSUAL** | **~$5,073** |

**Costo por llamada**: $5,073 / 12,500 llamadas = **$0.41/llamada**

**Distribución por región** (asumiendo distribución equitativa):
- us-east-1: ~167 llamadas/día
- eu-north-1: ~167 llamadas/día
- ap-northeast-1: ~166 llamadas/día

### Resumen de Escenarios

| Escenario | Llamadas/Día | Llamadas/Mes | Costo Mensual | Costo/Llamada | Arquitectura |
|-----------|-------------|--------------|---------------|---------------|--------------|
| **Desarrollo/POC** | 5 | 125 | $59 | $0.47 | Single t3.micro |
| **Testing** | 50 | 1,250 | $513 | $0.41 | Single t3.small |
| **Producción Pequeña** | 100 | 2,500 | $1,026 | $0.41 | Single t3.medium |
| **Producción Media + HA** | 200 | 5,000 | $2,084 | $0.42 | 2× t3.medium + NLB |
| **Producción Alta Multi-Región** | 500 | 12,500 | $5,073 | $0.41 | 3 regiones × t3.medium |

**Observación clave**: El costo por llamada se mantiene relativamente constante (~$0.41-$0.47) independiente de la escala, ya que Nova Sonic (componente principal) escala linealmente.

---

## Comparativa por Región

Los precios de Nova Sonic son consistentes entre regiones, pero los costos de infraestructura varían.

### Precios de EC2 t3.medium por Región

| Región | Código AWS | Precio/hora | Costo Mensual<br/>(730 hrs) | Diferencia vs<br/>us-east-1 |
|--------|------------|-------------|---------------------------|----------------------------|
| **US East (N. Virginia)** | us-east-1 | $0.0416 | $30.37 | Baseline |
| **Europe (Stockholm)** | eu-north-1 | $0.0456 | $33.29 | +9.6% |
| **Asia Pacific (Tokyo)** | ap-northeast-1 | $0.0544 | $39.71 | +30.8% |

### Impacto en Costo Total (Escenario: 100 llamadas/día)

| Región | Bedrock<br/>(Nova) | EC2<br/>t3.medium | Data Transfer | Logs | **Total Mensual** | Diferencia |
|--------|-------------------|------------------|---------------|------|------------------|------------|
| **us-east-1** | $958 | $30.37 | $18.00 | $20.00 | **$1,026** | Baseline |
| **eu-north-1** | $958 | $33.29 | $18.00 | $20.00 | **$1,029** | +0.3% |
| **ap-northeast-1** | $958 | $39.71 | $20.00 | $20.00 | **$1,038** | +1.2% |

**Conclusión**: El costo de Nova Sonic (>93% del total) domina el costo total, por lo que las diferencias regionales de infraestructura son mínimas (~1-2%).

**Recomendación**: Elegir región basándose en **latencia** y **proximidad a usuarios** en lugar de precio, ya que el impacto en costo es negligible.

### Data Transfer Pricing (Outbound)

| Región | Primeros 10 TB/mes | 10-50 TB/mes | 50-150 TB/mes |
|--------|-------------------|--------------|---------------|
| **us-east-1** | $0.09/GB | $0.085/GB | $0.070/GB |
| **eu-north-1** | $0.09/GB | $0.085/GB | $0.070/GB |
| **ap-northeast-1** | $0.114/GB | $0.089/GB | $0.086/GB |

Para volúmenes típicos del gateway (<1 TB/mes), las diferencias son mínimas.

---

## Comparativa por Tipo de Instancia

Comparativa de costos mensuales de infraestructura (sin Nova Sonic) para diferentes tipos de instancia.

### Single-Instance Architecture (1 instancia)

| Tipo Instancia | vCPU | RAM | Precio/hora<br/>(us-east-1) | Costo Mensual<br/>(730 hrs) | Llamadas<br/>Máx Simultáneas | Costo Infra/<br/>Llamada* |
|----------------|------|-----|---------------------------|---------------------------|----------------------------|-------------------------|
| **t3.micro** | 2 | 1 GB | $0.0104 | $7.59 | 3-5 | $0.030 |
| **t3.small** | 2 | 2 GB | $0.0208 | $15.18 | 10-15 | $0.012 |
| **t3.medium** | 2 | 4 GB | $0.0416 | $30.37 | 20 | $0.012 |
| **t3.large** | 2 | 8 GB | $0.0832 | $60.74 | 20** | $0.024 |
| **c5.large** | 2 | 4 GB | $0.085 | $62.05 | 20** | $0.025 |
| **c5.xlarge** | 4 | 8 GB | $0.17 | $124.10 | 20** | $0.050 |

\* Asumiendo 100 llamadas/día (2,500/mes)
\** Limitado por cuota de Nova Sonic (20 sesiones), no por recursos de instancia

### Multi-Instance con HA (2 instancias + NLB)

| Configuración | Costo EC2 | Costo NLB | Costo Total Infra | Llamadas Máx | Costo Infra/<br/>Llamada* |
|--------------|-----------|-----------|-------------------|-------------|-------------------------|
| **2× t3.small + NLB** | $30.36 | $31.43 | $61.79 | 20** | $0.025 |
| **2× t3.medium + NLB** | $60.74 | $31.43 | $92.17 | 20** | $0.037 |
| **2× c5.large + NLB** | $124.10 | $31.43 | $155.53 | 20** | $0.062 |

\* Asumiendo 100 llamadas/día (2,500/mes)
\** Aún limitado por cuota de Nova Sonic, pero con alta disponibilidad

### Recomendaciones por Costo/Beneficio

| Escenario | Instancia Recomendada | Razón |
|-----------|----------------------|-------|
| **Desarrollo** | t3.micro | Suficiente para 3-5 llamadas, mínimo costo |
| **Testing (< 15 llamadas)** | t3.small | Buen balance costo/capacidad |
| **Producción < 20 llamadas** | t3.medium | Suficiente para límite de Nova, memoria adecuada |
| **Producción con HA** | 2× t3.medium + NLB | Alta disponibilidad sin sobrecosto |
| **Producción CPU-intensiva** | c5.large | CPU dedicada (no burst), mejor para cargas sostenidas |
| **Producción > 20 llamadas** | Multi-región (ver INFRAESTRUCTURA.md) | Única forma de superar límite de 20 |

⚠️ **Importante**: Instancias más grandes que t3.medium no aumentan capacidad de llamadas debido al límite de Nova (20 sesiones). Solo útiles si:
- Necesitas CPU dedicada (instancias C5)
- Tienes otros procesos en la misma instancia
- Planeas solicitar aumento de cuota a AWS

---

## Fórmulas de Cálculo

Para estimar costos personalizados según tu caso de uso.

### Variables de Entrada

| Variable | Símbolo | Ejemplo |
|----------|---------|---------|
| Llamadas por día | `C` | 100 |
| Duración promedio (minutos) | `D` | 5 |
| Días operacionales por mes | `M` | 25 |
| Porcentaje input (caller habla) | `I%` | 50% |
| Porcentaje output (Nova habla) | `O%` | 50% |
| Tipo de instancia | - | t3.medium |
| Región AWS | - | us-east-1 |

### Fórmulas

#### 1. Tokens de Audio Mensuales

```
Total minutos/mes = C × D × M

Tokens audio input = Total minutos × 4,500 tokens/min × I%
Tokens audio output = Total minutos × 4,500 tokens/min × O%
```

Ejemplo (100 llamadas/día, 5 min, 25 días):
```
Total minutos = 100 × 5 × 25 = 12,500 min/mes

Tokens input = 12,500 × 4,500 × 0.5 = 28,125,000 tokens
Tokens output = 12,500 × 4,500 × 0.5 = 28,125,000 tokens
```

#### 2. Costo de Nova Sonic

```
Costo audio input = (Tokens input / 1,000) × $0.0034
Costo audio output = (Tokens output / 1,000) × $0.0136
Costo text = (C × M × 300 tokens / 1,000) × $0.00024

Costo total Nova = Costo audio input + Costo audio output + Costo text
```

Ejemplo:
```
Costo input = (28,125,000 / 1,000) × $0.0034 = $95.63
Costo output = (28,125,000 / 1,000) × $0.0136 = $382.50
Costo text = (100 × 25 × 300 / 1,000) × $0.00024 = $1.80

Total Nova = $95.63 + $382.50 + $1.80 = $479.93
```

#### 3. Costo de Infraestructura

```
Costo EC2 = Precio_hora_instancia × 730 horas

Costo data transfer = Total minutos × 1.6 MB/min × $0.09/GB / 1024
  (asumiendo ~1.6 MB/min para RTP + Bedrock)

Costo logs = (Total minutos × 0.2 MB/min / 1024) × $0.50/GB
  (asumiendo ~0.2 MB/min de logs)
```

Ejemplo (t3.medium, us-east-1):
```
EC2 = $0.0416 × 730 = $30.37

Data transfer = 12,500 × 1.6 / 1024 × $0.09 = $1.76
  (puede variar, estimado conservador ~$18 considerando overhead)

Logs = 12,500 × 0.2 / 1024 × $0.50 = $1.22
  (puede variar, estimado conservador ~$20 con todos los logs)
```

#### 4. Costo Total y Por Llamada

```
Costo total mensual = Costo Nova + Costo EC2 + Costo data transfer + Costo logs

Costo por llamada = Costo total mensual / (C × M)
```

Ejemplo:
```
Total = $479.93 + $30.37 + $18.00 + $20.00 = $548.30

Por llamada = $548.30 / (100 × 25) = $0.22/llamada
```

### Calculadora Simplificada

Para un cálculo rápido:

```
Costo Nova ≈ C × D × M × $0.38  (por minuto de conversación)
Costo Infra ≈ $30 - $100/mes  (según tipo de instancia)

Total ≈ Costo Nova + Costo Infra
```

Ejemplo (100 llamadas/día × 5 min × 25 días):
```
Nova = 100 × 5 × 25 × $0.38 = $4,750  ❌ INCORRECTO

CORRECTO:
Nova por minuto ≈ $0.0383/min
  = (4,500 tokens/min × $0.0034 input) + (4,500 tokens/min × $0.0136 output)
  = $0.0153 + $0.0612 = $0.0765/min total (input+output)

Pero si dividimos 50/50:
  Input: 4,500 × 0.5 × $0.0034 = $0.00765/min
  Output: 4,500 × 0.5 × $0.0136 = $0.0306/min
  Total: $0.03825/min ≈ $0.038/min

Nova = 12,500 min × $0.038 = $475
Infra = $30-70
Total ≈ $500-550
```

---

## Optimización de Costos

Estrategias para reducir costos sin sacrificar funcionalidad.

### 1. Optimizar Duración de Llamadas

**Impacto**: Nova Sonic es ~93% del costo total y escala linealmente con duración.

| Si reduces duración promedio de | A | Ahorro mensual<br/>(100 llamadas/día) |
|-------------------------------|---|-----------------------------------|
| 5 minutos | 4 minutos | ~$192 (20%) |
| 5 minutos | 3 minutos | ~$384 (40%) |

**Estrategias**:
- Optimizar system prompt para respuestas más concisas
- Configurar `NOVA_MAX_TOKENS` a valores más bajos (ej: 512 en lugar de 1024)
- Implementar timeouts por inactividad
- Entrenar a los usuarios en flujos eficientes

### 2. Ajustar Configuración de Nova Sonic

En `NovaMediaConfig.java` o variables de entorno:

```bash
# Reducir tokens máximos por respuesta
NOVA_MAX_TOKENS=512  # Default: 1024 (reduce a la mitad si es apropiado)

# Ajustar temperatura para respuestas más directas
NOVA_TEMPERATURE=0.5  # Default: 0.7 (valores más bajos = más determinístico/conciso)
```

**Impacto estimado**: 10-30% reducción en tokens output sin sacrificar calidad.

### 3. Usar Instancias Apropiadas

**No sobre-provisionar**: t3.large vs t3.medium tiene 0 beneficio de capacidad (límite es Nova).

| Configuración | Costo Mensual | Capacidad Máx | Notas |
|--------------|---------------|---------------|-------|
| **t3.medium (recomendado)** | $30.37 | 20 llamadas | Suficiente para límite Nova |
| t3.large (desperdicio) | $60.74 | 20 llamadas | 2× costo, 0× beneficio |

**Ahorro**: $30/mes al elegir instancia apropiada.

### 4. Optimizar Rangos de Puertos RTP

Si sabes que nunca tendrás más de X llamadas simultáneas:

```bash
# En lugar de default 10,000 puertos:
MEDIA_PORT_BASE=10000
MEDIA_PORT_COUNT=100  # Suficiente para 50 llamadas máx

# Reduce rango de security group (menor superficie de ataque)
# Y potencialmente menor overhead de kernel
```

**Impacto**: Principalmente seguridad, impacto mínimo en costo directo.

### 5. Reducir Logs

CloudWatch Logs puede sumar ~$10-100/mes según volumen.

```bash
# Desactivar logs de SIP detallados en producción
DEBUG_SIP=false  # Default: true

# Desactivar logs de audio detallados
DEBUG_AUDIO_OUTPUT=false  # Default: false
```

**Configurar retención de logs**:
```typescript
// En CDK
logGroup.applyRemovalPolicy(RemovalPolicy.DESTROY);
logGroup.retention = logs.RetentionDays.ONE_WEEK;  // En vez de INFINITE
```

**Ahorro potencial**: $5-20/mes

### 6. Aprovechar Savings Plans / Reserved Instances

Si tu carga es predecible (24/7):

| Compromiso | Instancia | On-Demand | 1-Year RI | Ahorro |
|-----------|-----------|-----------|-----------|--------|
| 1 año | t3.medium | $30.37/mes | $19.64/mes | 35% |
| 3 años | t3.medium | $30.37/mes | $13.14/mes | 57% |

**Ahorro**: $10-17/mes por instancia con compromiso de 1-3 años.

⚠️ **Precaución**: Solo si estás seguro de uso continuo. No recomendado para POC/dev.

### 7. Multi-Región Solo Cuando Necesario

Si no necesitas más de 20 llamadas simultáneas, **no despliegues multi-región**.

| Arquitectura | Costo | Capacidad | Cuándo usar |
|-------------|-------|-----------|-------------|
| Single-region | ~$1,000/mes | 20 llamadas | Suficiente para la mayoría |
| Multi-region (3) | ~$3,000/mes | 60 llamadas | Solo si excedes 20 simultáneas |

**Ahorro**: $2,000/mes si no necesitas multi-región.

### 8. Usar Spot Instances para Testing

**Solo para entornos de desarrollo/testing**:

```typescript
// En CDK
spotPrice: '0.015'  // ~60% descuento vs on-demand t3.medium
```

**Ahorro**: ~$18/mes para instancia de testing.

⚠️ **NO usar Spot para producción** - pueden terminarse sin aviso.

### 9. Implementar Circuit Breaker

Prevenir costos inesperados si hay un problema:

```java
// Pseudocódigo - no implementado actualmente
if (activeCallsInLastHour > THRESHOLD) {
    sendAlert("Unusual call volume detected");
    if (activeCallsInLastHour > CRITICAL_THRESHOLD) {
        // Rechazar nuevas llamadas temporalmente
        rejectNewCalls();
    }
}
```

Protege contra:
- Bucles infinitos de llamadas
- Ataques de denegación de servicio (DoS)
- Errores de configuración que causen reconexiones constantes

### 10. Monitorear y Alertar sobre Costos

Configurar AWS Budgets:

```typescript
new budgets.CfnBudget(this, 'VoipBudget', {
  budget: {
    budgetName: 'VoIPGatewayMonthly',
    budgetLimit: {
      amount: 1500,  // $1,500/mes
      unit: 'USD'
    },
    timeUnit: 'MONTHLY',
    budgetType: 'COST'
  },
  notificationsWithSubscribers: [{
    notification: {
      notificationType: 'ACTUAL',
      comparisonOperator: 'GREATER_THAN',
      threshold: 80  // Alerta al 80% del budget
    },
    subscribers: [{
      subscriptionType: 'EMAIL',
      address: 'admin@example.com'
    }]
  }]
});
```

### Resumen de Optimizaciones

| Optimización | Ahorro Potencial | Esfuerzo | Riesgo |
|-------------|-----------------|----------|--------|
| Reducir duración de llamadas | 20-40% | Medio | Bajo |
| Ajustar NOVA_MAX_TOKENS | 10-30% | Bajo | Bajo |
| Instancia apropiada | $30/mes | Bajo | Ninguno |
| Reducir logs | $10-20/mes | Bajo | Bajo |
| Reserved Instances | 35-57% infra | Bajo | Medio (compromiso) |
| Evitar multi-región innecesaria | $2,000/mes | Ninguno | Ninguno |
| Circuit breaker | Variable | Alto | Bajo |
| Spot para testing | $18/mes | Bajo | Ninguno (solo testing) |

**Máximo ahorro realista sin impactar funcionalidad**: ~30-50% optimizando configuración de Nova y eligiendo infraestructura apropiada.

---

## Resumen Ejecutivo

### Desglose de Costos Típico (100 llamadas/día, 5 min promedio)

```
Total Mensual: ~$1,026

┌─────────────────────────────────────────┐
│ Nova Sonic Audio Output:    $765 (74%) │ ← Componente principal
│ Nova Sonic Audio Input:     $191 (19%) │
│ EC2 t3.medium:               $30  (3%)  │
│ Data Transfer:               $18  (2%)  │
│ CloudWatch Logs:             $20  (2%)  │
│ Nova Sonic Text:             $2   (<1%) │
└─────────────────────────────────────────┘
```

## Tabla tecnica del modelo
| **Atributo**                                   | **Detalle** |
|------------------------------------------------|-------------|
| **Nombre del modelo**                          | Amazon Nova Sonic |
| **ID del modelo**                              | `amazon.nova-sonic-v1:0` |
| **Modalidades de entrada**                     | Discurso |
| **Modalidades de salida**                      | Discurso con transcripción y respuestas de texto |
| **Ventana de contexto**                        | 300K contexto |
| **Duración máxima de la conexión**             | Tiempo de espera de conexión de 8 minutos, con un máximo de 20 conexiones simultáneas por cliente. *(1)* |
| **Idiomas compatibles** *(2)*                  | Inglés (EE. UU., Reino Unido), francés, italiano, alemán y español |
| **Regiones**                                   | Este de EE. UU. (Norte de Virginia), Europa (Estocolmo) y Asia Pacífico (Tokio) |
| **Compatibilidad con API de transmisión bidireccional** | Sí |
| **Bases de conocimiento de Bedrock**           | Compatible mediante el uso de herramientas (llamada a funciones). |

> *(1)* El límite de conexión es de 8 minutos por defecto, pero puede renovarse enviando el historial de conversación anterior.  
> *(2)* Soporte actual para los idiomas listados.


**Conclusiones clave:**
1. **Nova Sonic domina el costo** (~93% del total)
2. **Costo por llamada consistente** en ~$0.41/llamada independiente de escala
3. **Región tiene mínimo impacto** en costo total (<2%)
4. **Instancias más grandes no aumentan capacidad** debido a límite de 20 sesiones Nova
5. **Multi-región es única forma** de superar 20 llamadas simultáneas (3× costo para 3× regiones)

**Próximos pasos**: Ver [`docs/OPERACIONES.md`](OPERACIONES.md) para guía de monitoreo de costos en tiempo real.
