# Guía de Ejecución - Gateway VoIP Nova S2S

Esta guía proporciona instrucciones paso a paso para configurar y ejecutar el Gateway VoIP Nova S2S.

## Requisitos Previos

Antes de comenzar, asegúrate de tener:

- **Cuenta SIP**: Una cuenta SIP en un servidor SIP (proveedor VoIP público o tu propio PBX)
- **Java JDK**: Java 9 o superior ([Corretto](https://aws.amazon.com/corretto), [OpenJDK](https://developers.redhat.com/products/openjdk/overview), u [Oracle JDK](https://www.oracle.com/java/technologies/downloads/))
- **Apache Maven**: Para compilación ([Descargar](https://maven.apache.org/))
- **Node.js**: Requerido para CDK ([Descargar](https://nodejs.org/en/download))
- **AWS CDK**: Para despliegues ([Guía de instalación](https://docs.aws.amazon.com/cdk/v2/guide/getting_started.html))
- **Docker**: Solo si usas despliegue ECS ([Descargar](https://docs.docker.com/get-started/get-docker/))
- **AWS Credentials**: Configuradas en tu entorno para acceder a Bedrock
- **Conocimientos básicos**: Voice over IP (VoIP) y SIP

⚠️ **Nota Importante:** Este es solo una prueba de concepto y no debe considerarse código listo para producción.

## 1. Configuración de Maven

Maven requiere credenciales para acceder al repositorio de GitHub donde se aloja mjSIP.

### Crear Token de GitHub

1. Ve a https://github.com/settings/tokens
2. Crea un **Classic Token** con permisos de lectura de paquetes

### Configurar settings.xml

Crea o edita `~/.m2/settings.xml` (en Windows: `C:\Users\{username}\.m2\settings.xml`):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 http://maven.apache.org/xsd/settings-1.0.0.xsd">
    <servers>
        <server>
            <id>github</id>
            <username>TU_USUARIO</username>
            <password>TU_TOKEN_DE_AUTENTICACION</password>
        </server>
    </servers>
</settings>
```

Reemplaza `TU_USUARIO` y `TU_TOKEN_DE_AUTENTICACION` con tus credenciales de GitHub.

## 2. Configuración de Variables de Entorno

El proyecto puede configurarse mediante variables de entorno o archivo `.mjsip-ua`. **Si `SIP_SERVER` está configurado, se usarán las variables de entorno; de lo contrario, se usará `.mjsip-ua`.**

### Crear archivo .env

Copia `env.example` a `.env` y configura los valores según tu entorno:

```bash
cp env.example .env
```

### Variables Críticas (Requeridas)

```bash
# Configuración SIP
export SIP_SERVER="tu-servidor-sip.com"        # Hostname/IP del servidor SIP
export SIP_USER="tu_usuario"                    # Usuario SIP
export AUTH_USER="tu_usuario"                   # Usuario de autenticación
export AUTH_PASSWORD="tu_password"              # Password de autenticación
export AUTH_REALM="tu_realm"                    # Realm SIP
export DISPLAY_NAME="Nombre Display"            # Nombre para mostrar
```

### Variables de Configuración Multi-Cliente (Importante)

```bash
# Sistema de Prompts Multi-Cliente
export CLIENT_ID="keralty"                      # Directorio de prompts a usar
# Determina qué prompts se cargan desde src/main/resources/prompts/{CLIENT_ID}/
```

### Variables de Nova Sonic

```bash
# Configuración de Nova
export NOVA_VOICE_ID="en_us_matthew"            # Voz de Nova Sonic
export NOVA_MAX_TOKENS=1024                     # Máximo de tokens por respuesta
export NOVA_TEMPERATURE=0.7                     # Temperatura de generación
export NOVA_TOP_P=0.9                           # Top-p sampling

# Prompt del sistema (opcional - sobrescribe CLIENT_ID)
export NOVA_PROMPT="$(cat ~/sample-s2s-voip-gateway/src/main/resources/prompts/${CLIENT_ID}/base-prompt.txt)"
```

### Variables Opcionales (con defaults)

```bash
# Direcciones de Red (auto-detectadas si no se configuran)
export SIP_VIA_ADDR="auto-detected"             # IP para campo Via en SIP
export MEDIA_ADDRESS="auto-detected"            # IP para tráfico RTP

# Configuración de Medios
export MEDIA_PORT_BASE=10000                    # Primer puerto RTP
export MEDIA_PORT_COUNT=10000                   # Tamaño del pool de puertos RTP
export GREETING_FILENAME="hello-how.wav"        # Archivo de saludo

# Keep-Alive
export SIP_KEEPALIVE_TIME=60000                 # Frecuencia en milisegundos

# Debug
export DEBUG_SIP=true                           # Logging de paquetes SIP
export DEBUG_AUDIO_OUTPUT=false                 # Logging de audio
```

### Cargar Variables de Entorno

```bash
# Linux/Mac
source .env

# Windows (PowerShell)
Get-Content .env | ForEach-Object {
    if ($_ -match '^export\s+(.+?)=(.+)$') {
        [System.Environment]::SetEnvironmentVariable($matches[1], $matches[2].Trim('"'), "Process")
    }
}
```

## 3. Compilación

Compila el proyecto con Maven:

```bash
mvn package
```

Esto creará `target/s2s-voip-gateway-<version>.jar` con todas las dependencias incluidas (uber-JAR usando Maven Shade plugin).

## 4. Opciones de Ejecución

### Opción A: Ejecución Local Rápida

La forma más rápida de probar el gateway:

```bash
./run.sh
```

Este script compila y ejecuta automáticamente la clase principal: `com.example.s2s.voipgateway.NovaSonicVoipGateway`

**Verificación:**
1. Observa los logs para confirmar registro SIP exitoso (respuesta 200)
2. Llama al número/extensión de tu cuenta SIP
3. El gateway debe responder y reproducir el saludo
4. Conversa con Nova Sonic
5. Presiona `Ctrl-C` para salir

### Opción B: Despliegue en EC2 (Desarrollo/Testing)

Ideal para desarrollo y pruebas. Crea una instancia EC2 con toda la configuración necesaria.

#### Instalación

1. **Crear Key Pair** (si no tienes uno):
   - Ve a la consola EC2 en AWS
   - Crea un nuevo key pair y descárgalo

2. **Configurar CDK**:
   ```bash
   cd cdk-ec2-instance
   ```

3. **Editar configuración**:
   - Abre `bin/cdk.ts` en un editor
   - Actualiza `keyPairName` con el nombre de tu key pair
   - (Opcional) Descomenta y configura `vpcId` si quieres usar un VPC existente

4. **Desplegar**:
   ```bash
   npm install
   cdk bootstrap  # Solo primera vez
   cdk deploy
   ```

5. **CDK mostrará la IP de tu instancia**

#### ¿Qué crea este stack?

- VPC nuevo (o usa uno existente si lo configuraste)
- Rol IAM con permisos para Bedrock
- Security Groups con puertos VoIP configurados
- Instancia EC2 con Amazon Linux, JDK (Corretto), Maven y Git preinstalados

#### Ejecutar en EC2

1. **Conectar por SSH**:
   ```bash
   ssh -i tu-keypair.pem ec2-user@{IP_DE_LA_INSTANCIA}
   ```

2. **Copiar o clonar el proyecto**:
   ```bash
   git clone {URL_DEL_REPO}
   cd sample-s2s-voip-gateway
   ```

3. **Configurar Maven settings.xml** (ver sección 1)

4. **Configurar variables de entorno** (ver sección 2)

5. **Ejecutar**:
   ```bash
   ./run.sh
   ```

6. **Verificar** registro SIP en los logs (debe obtener respuesta 200)

7. **Llamar** al número/extensión de tu línea SIP

#### Limpieza

```bash
cd cdk-ec2-instance
cdk destroy
```

### Opción C: Despliegue en ECS (Production-like)

Para un entorno más cercano a producción usando contenedores ECS con EC2 en modo host.

#### Requisitos Adicionales

- Docker instalado y corriendo en tu estación de trabajo

#### Instalación

1. **Compilar el proyecto**:
   ```bash
   mvn package
   ```

2. **Copiar JAR a directorio Docker**:
   ```bash
   cp target/s2s-voip-gateway-<version>.jar docker/
   ```

3. **Configurar CDK**:
   ```bash
   cd cdk-ecs
   cp cdk.context.json.template cdk.context.json
   ```

4. **Editar configuración**:
   - Abre `cdk.context.json` en un editor
   - Configura todos los parámetros (credenciales SIP, configuración Nova, etc.)

5. **Desplegar**:
   ```bash
   npm install
   cdk bootstrap  # Solo primera vez
   cdk deploy
   ```

6. **Probar**: Llama al número/extensión de tu cuenta SIP

#### ¿Qué crea este stack?

- VPC dedicado para la instalación
- VPC Endpoints para ECR (Elastic Container Registry)
- ECS Cluster
- Auto Scaling Group
- Task execution roles y task roles
- Secrets Manager para credenciales SIP
- ECS Task y Service para el Gateway VoIP

#### Limpieza

```bash
cd cdk-ecs
cdk destroy
```

## 5. Configuración de Red

mjSIP no tiene capacidades de uPNP, ICE o STUN, por lo que necesitas configurar los security groups apropiados.

### Reglas de Entrada Requeridas

```
Protocolo: UDP
Puerto: 5060
Descripción: SIP signaling

Protocolo: UDP
Puertos: 10000-20000
Descripción: RTP media traffic (configurable vía MEDIA_PORT_BASE/MEDIA_PORT_COUNT)
```

### Reglas de Salida

```
Permitir todo el tráfico saliente
```

### Sobrescribir Direcciones IP

Si necesitas especificar direcciones IP manualmente (útil para troubleshooting):

```bash
export SIP_VIA_ADDR="tu.ip.publica"      # IP para campo Via en mensajes SIP
export MEDIA_ADDRESS="tu.ip.publica"     # IP para tráfico RTP
```

## 6. Verificación y Troubleshooting

### Verificar Registro SIP Exitoso

En los logs, busca:
```
200 OK
```

Si ves errores de autenticación (401, 403), verifica:
- `AUTH_USER`, `AUTH_PASSWORD`, `AUTH_REALM` son correctos
- Tu cuenta SIP está activa
- Las credenciales en Secrets Manager (ECS) coinciden

### Problemas Comunes

**El gateway no responde llamadas:**
- Verifica que los security groups permitan UDP 5060 y 10000-20000
- Confirma que `SIP_VIA_ADDR` y `MEDIA_ADDRESS` son accesibles desde el servidor SIP
- Revisa los logs para errores de registro

**Audio no se escucha:**
- Verifica que el rango de puertos RTP esté abierto en ambas direcciones
- Confirma que `MEDIA_ADDRESS` es la IP correcta para RTP
- Revisa `DEBUG_AUDIO_OUTPUT=true` para logs detallados de audio

**Maven falla al compilar:**
- Verifica que `~/.m2/settings.xml` está configurado correctamente
- Confirma que tu token de GitHub tiene permisos de lectura de paquetes
- Prueba acceso manual al repositorio

**Nova Sonic no responde:**
- Verifica que tus credenciales AWS tienen acceso a Bedrock
- Confirma que estás en una región donde Nova Sonic está disponible (us-east-1, eu-north-1, ap-northeast-1)
- Revisa que `CLIENT_ID` apunta a un directorio válido con prompts

### Habilitar Debug

Para más información en los logs:

```bash
export DEBUG_SIP=true
export DEBUG_AUDIO_OUTPUT=true
```

## 7. Sistema de Prompts Multi-Cliente

El gateway soporta múltiples configuraciones de cliente mediante directorios de prompts.

### Estructura de Directorios

```
src/main/resources/prompts/
├── keralty/                    # Cliente por defecto
│   ├── base-prompt.txt
│   ├── context-citas.txt
│   ├── context-pqrs.txt
│   └── context-imagenes.txt
├── {tu-cliente}/
│   ├── base-prompt.txt
│   └── context-*.txt
```

### Configurar Cliente

```bash
export CLIENT_ID="keralty"      # Usa prompts de prompts/keralty/
```

### Sobrescribir con Prompt Custom

Si necesitas control total del prompt:

```bash
export NOVA_PROMPT="Tu prompt personalizado aquí..."
# Esto ignora CLIENT_ID y usa el prompt directamente
```

## Documentación Adicional

Para información sobre capacidad, costos, escalabilidad y operaciones, consulta:

- **[INFRAESTRUCTURA-Y-ESCALABILIDAD.md](INFRAESTRUCTURA-Y-ESCALABILIDAD.md)** - Capacidad por tipo de instancia, arquitecturas de despliegue, multi-región
- **[COSTOS-Y-PRECIOS.md](COSTOS-Y-PRECIOS.md)** - Análisis de costos por escenario y región
- **[OPERACIONES.md](OPERACIONES.md)** - Monitoreo, limitaciones, troubleshooting avanzado

## Soporte

Para preguntas sobre arquitectura, consulta el [README.md](../README.md) principal que explica cómo funciona el sistema.
