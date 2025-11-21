# Gateway VoIP Nova S2S

Este proyecto contiene una implementación de un endpoint SIP que actúa como gateway hacia Nova Sonic speech-to-speech de Amazon.
En otras palabras, puedes llamar a un número de teléfono y hablar con Nova Sonic.

<!-- TOC -->

- [¿Cómo funciona?](#cómo-funciona)
- [Comenzando con ECS y CDK](#comenzando-con-ecs-y-cdk)
- [Comenzando con EC2](#comenzando-con-ec2)
- [Dependencias de Terceros Importantes](#dependencias-de-terceros-importantes)
- [Variables de Entorno](#variables-de-entorno)
- [Redes](#redes)
- [Compilación](#compilación)
- [Configuración de Maven settings.xml](#configuración-de-maven-settingsxml)
- [Guía para Desarrolladores](#guía-para-desarrolladores)
- [Documentación Técnica](#documentación-técnica)
- [Licencia](#licencia)
<!-- TOC -->

**Requisitos:**

- Una cuenta SIP en un servidor SIP. Hay varias opciones disponibles, ya sea un proveedor VoIP público o una cuenta en tu propio PBX.
- Tu estación de trabajo debe tener Node.js instalado. Esto es requerido para CDK. Ver https://nodejs.org/en/download.
- Tu estación de trabajo debe tener CDK instalado. Ver https://docs.aws.amazon.com/cdk/v2/guide/getting_started.html.

Debes tener conocimientos básicos de Voice over IP (VoIP) y SIP.

**⚠️ Nota Importante:** Este es solo una prueba de concepto y no debe considerarse código listo para producción.

## ¿Cómo funciona?

Esta aplicación actúa como un agente de usuario SIP. Cuando inicia, se registra con un servidor SIP. Al recibir una llamada, responderá, establecerá la sesión de medios (sobre RTP), iniciará una sesión con Nova Sonic y puenteará el audio entre RTP y Nova Sonic. El audio recibido vía RTP se envía a Nova Sonic y el audio recibido de Nova Sonic se envía a la persona que llama vía RTP.

![](flow.png)

## Comenzando con ECS y CDK

Esta aplicación puede ejecutarse en un contenedor ECS respaldado por EC2 corriendo en modo host. Esto le permite enlazar rangos grandes de puertos UDP que son requeridos para RTP. Esta guía detalla cómo instalar usando infraestructura como código con CDK.

![](architecture.png)

**Requisitos Adicionales:**

- Tu estación de trabajo debe tener Docker instalado y Docker debe estar corriendo. Esto es requerido para construir la imagen Docker. Ver https://docs.docker.com/get-started/get-docker/.

**Instalación:**

1. Compila el proyecto Maven. Ver la sección de compilación para detalles.
2. Copia `target/s2s-voip-gateway-<version>.jar` al directorio docker/
3. Copia `cdk-ecs/cdk.context.json.template` a `cdk-ecs/cdk.context.json`
4. Abre `cdk-ecs/cdk.context.json` en tu editor de texto favorito y configura cada uno de los parámetros.
5. Desde una terminal ejecuta lo siguiente:
   ```bash
   cd cdk-ecs
   npm install
   cdk bootstrap
   cdk deploy
   ```
6. Una vez que el proyecto esté completamente desplegado, intenta llamar al número de teléfono o extensión de tu cuenta SIP. El gateway debe responder inmediatamente y saludarte.
7. Conversa con Nova Sonic.

**¿Qué hace este stack de CDK?**

- Crea un VPC para tu instalación
- Crea endpoints de VPC para Elastic Container Registry (ECR)
- Crea un clúster de Elastic Container Service (ECS)
- Crea un grupo de auto-escalado
- Crea roles de ejecución de tareas y tareas
- Crea secretos para tus credenciales SIP
- Crea una tarea y servicio para el Gateway VoIP

**Limpieza:**

```bash
cd cdk-ecs
cdk destroy
```

## Comenzando con EC2

El Gateway VoIP Nova S2S puede ejecutarse en una configuración tan simple como una sola instancia EC2. Si estás desarrollando y probando cambios, este es el enfoque recomendado.

Hemos incluido un stack de CDK para crear una instancia EC2 con los permisos apropiados y grupos de seguridad configurados. Para instalarlo, haz lo siguiente:

1. Si aún no tienes un keypair, crea uno desde la consola EC2. Esto es necesario para autenticarte en tu instancia.
2. (opcional) Si prefieres usar un VPC existente, edita `cdk-ec2-instance/bin/cdk.ts`, descomenta la línea con vpcId y actualízala a tu VPC existente. El VPC debe tener subnets públicas.
3. Abre `cdk-ec2-instance/bin/cdk.ts` en un editor de texto y actualiza keyPairName al nombre del keypair existente o recién creado.
4. Desde una terminal ejecuta lo siguiente:
   ```bash
   cd cdk-ec2-instance
   npm install
   cdk bootstrap
   cdk deploy
   ```
5. CDK mostrará la dirección IP de tu instancia EC2 recién creada.

**¿Qué hace este stack de CDK?**

- Crea un nuevo VPC para tu instancia (a menos que esté configurado para usar uno existente)
- Crea un rol IAM para tu instancia
- Crea grupos de seguridad para tu instancia
- Crea la instancia EC2 con Amazon Linux, configurada para instalar un JDK (Amazon Corretto), Maven y Git

**Para ejecutar el proyecto:**

1. Conecta por SSH a la instancia EC2 usando el keypair del paso 1 de la guía de instalación y la dirección IP del paso 5.
2. Copia el proyecto desde tu computadora local o clónalo con git en tu instancia EC2.
3. Configura tu Maven settings.xml como se detalla en la sección de configuración de Maven settings.xml.
4. Ejecuta el proyecto de la siguiente manera: `./run.sh` (esto compilará y ejecutará la clase principal)
5. Observa el registro SIP. Asegúrate de que obtenga una respuesta 200. Si no, tus credenciales pueden ser incorrectas.
6. Llama al número de teléfono o extensión de tu línea SIP. El gateway debe responder inmediatamente y saludarte.
7. Conversa con Nova Sonic.
8. Presiona Ctrl-C para salir.

**Limpieza:**

Desde una terminal ejecuta lo siguiente:

```bash
cd cdk-ec2-instance
cdk destroy
```

## Dependencias de Terceros Importantes

Este proyecto utiliza un fork del proyecto mjSIP, que se puede encontrar en https://github.com/haumacher/mjSIP, el cual está licenciado bajo GPLv2.

## Variables de Entorno

Este proyecto puede configurarse para ejecutarse a través del archivo de configuración `.mjsip-ua` O configurando variables de entorno. A continuación se muestra una lista de las variables de entorno en uso:

- **AUTH_USER** - nombre de usuario para autenticación con el servidor SIP
- **AUTH_PASSWORD** - contraseña para autenticación con el servidor SIP
- **AUTH_REALM** - el reino SIP a usar para autenticación
- **DEBUG_SIP** - true|false para habilitar/deshabilitar el registro de paquetes SIP
- **DISPLAY_NAME** - el nombre para mostrar a enviar para tu dirección SIP
- **GREETING_FILENAME** - el nombre del archivo wav a reproducir como saludo. Puede ser una ruta absoluta o en el classpath.
- **MEDIA_ADDRESS** - la dirección IP a usar para el tráfico de medios RTP. Por defecto obtendrá la dirección de tus interfaces de red.
- **MEDIA_PORT_BASE** - el primer puerto RTP a usar para tráfico de audio
- **MEDIA_PORT_COUNT** - el tamaño del pool de puertos RTP usado para tráfico de audio
- **NOVA_PROMPT** - el prompt a usar con Amazon Nova. El valor por defecto se puede encontrar en NovaMediaConfig.java.
- **NOVA_VOICE_ID** - la voz de Amazon Nova Sonic a usar. Ver https://docs.aws.amazon.com/nova/latest/userguide/available-voices.html. Por defecto es matthew.
- **SIP_KEEPALIVE_TIME** - frecuencia en milisegundos para enviar paquetes keep-alive
- **SIP_SERVER** - el nombre de host o dirección IP del servidor SIP con el cual registrarse. Requerido si se ejecuta en modo de variables de entorno.
- **SIP_USER** - equivalente a sip-user del archivo `.mjsip-ua`, generalmente lo mismo que AUTH_USER
- **SIP_VIA_ADDR** - la dirección a enviar en paquetes SIP para el campo Via. Por defecto obtendrá la dirección de tus interfaces de red.

Si SIP_SERVER está configurado, la aplicación tomará la configuración de las variables de entorno. Si no está configurado, usará el archivo `.mjsip-ua`.

## Redes

mjSIP no contiene capacidades de uPNP, ICE o STUN, por lo que es necesario que tu instancia esté configurada con los grupos de seguridad apropiados para permitir tráfico VoIP.

**Reglas de entrada:**

- Permitir tráfico UDP entrante en el puerto 5060 (puerto SIP)
- Permitir tráfico UDP entrante en el rango de puertos 10000-20000 (puertos efímeros RTP). El rango puede configurarse a través de las variables de entorno MEDIA_PORT_BASE y MEDIA_PORT_COUNT. Configura esto a un valor apropiado para tu configuración.

**Reglas de salida:**

- Permitir todo el tráfico saliente.

Si quieres sobrescribir las direcciones que se usan con el tráfico SIP, puedes hacerlo ejecutando en modo de variables de entorno. Ver Variables de Entorno para más información sobre cómo hacer esto y qué se puede configurar.

## Compilación

El Gateway VoIP Nova S2S es un proyecto Java Maven. Como tal, requiere un JDK para compilar. El proyecto está configurado para compatibilidad con Java 9, pero puede compilarse con versiones mucho más recientes. Aquí hay algunas opciones:

- Corretto: https://aws.amazon.com/corretto
- OpenJDK: https://developers.redhat.com/products/openjdk/overview
- Oracle: https://www.oracle.com/java/technologies/downloads/

Además, se requiere Apache Maven para hacer la compilación. Esto puede descargarse desde https://maven.apache.org/ o instalarse en Amazon Linux usando el comando `sudo yum install maven`. Descomprime Maven en un lugar donde puedas encontrarlo nuevamente. Ver "Configuración de Maven settings.xml" abajo para detalles sobre cómo configurar Maven.

Para compilar el proyecto, abre una terminal y navega al directorio del proyecto. Ejecuta `mvn package` (puede que necesites poner la ruta completa /path/to/maven/bin/mvn si el directorio bin no está en tu PATH del sistema).

Maven compilará el proyecto y creará un archivo s2s-voip-gateway\*.jar en el directorio target/.

## Configuración de Maven settings.xml

mjSIP se distribuye desde un repositorio Maven de GitHub. Desafortunadamente, los repositorios Maven de GitHub requieren credenciales. Necesitarás configurar un token de API clásico con GitHub (https://github.com/settings/tokens), si aún no lo has hecho, y configurarlo en tu archivo ~/.m2/settings.xml:

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

## Guía para Desarrolladores

El punto de entrada para la aplicación es NovaSonicVoipGateway.java. Esta clase contiene un método main y configura el agente de usuario basándose en lo que encuentra en las variables de entorno.

El punto de entrada principal para la integración con Nova está en NovaStreamerFactory.java, donde se instancia el cliente de Bedrock y se establecen los flujos de audio.

Por defecto, el gateway incluye un conjunto de herramientas que le da a Nova Sonic la capacidad de recuperar la fecha y hora, pero esto puede extenderse para hacer mucho más. Las herramientas de ejemplo se pueden encontrar en com.example.s2s.voipgateway.nova.tools.

Nuevas herramientas pueden desarrollarse extendiendo la clase AbstractNovaS2SEventHandler e implementando la funcionalidad que desees. Ver el javadoc en AbstractNovaS2SEventHandler para más información. Un punto de partida fácil para nuevas herramientas sería copiar DateTimeNovaS2SEventHandler a un nuevo archivo, reemplazando las herramientas con algo relevante a tu caso de uso.

El conjunto de herramientas se instancia en NovaStreamerFactory.createMediaStreamer(). Si creas nuevas herramientas necesitarás actualizar el NovaS2SEventHandler para instanciar tu nueva clase.

## Documentación Técnica

Para información detallada sobre planificación de infraestructura, análisis de costos, escalabilidad y operaciones, consulta la documentación técnica en la carpeta `/docs`:

- **[Infraestructura y Escalabilidad](docs/INFRAESTRUCTURA-Y-ESCALABILIDAD.md)** - Capacidad por tipo de instancia, límites técnicos, arquitecturas de despliegue (single-instance y multi-instancia), estrategias multi-región
- **[Costos y Precios](docs/COSTOS-Y-PRECIOS.md)** - Pricing de Nova Sonic, análisis de costos por escenario, comparativas por región y tipo de instancia
- **[Operaciones](docs/OPERACIONES.md)** - Monitoreo, limitaciones arquitecturales actuales, troubleshooting y guía operacional

## Licencia

Licencia MIT-0. Ver el archivo LICENSE para más detalles.
