package com.example.s2s.voipgateway.tracing;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Call tracer que escribe variables externas a Amazon S3 al finalizar la llamada.
 *
 * Output format: {key}:{value} (plain text, una línea por variable)
 * S3 path: s3://{S3_BUCKET_NAME}/{client_id}/{sip_call_id}.txt
 *
 * Ejemplo:
 *   ani:573144779261
 *   call-id:1234567890abcdef@sip-provider.com
 *   client_id:keralty
 *   dnis:576105101000
 *   sip_call_id:1234567890abcdef@sip-provider.com
 *   x-client-name:jhonatan
 *   x-session-id:987654
 *
 * Design principles:
 * - Dinámico: Acepta Map con todas las variables (headers SIP + metadata)
 * - Lazy write: Solo escribe a S3 en close() (al finalizar la llamada)
 * - Fail-safe: Si S3 falla, loguea error pero no afecta la llamada
 */
@Slf4j
public class CallTracer implements AutoCloseable {

    private final Map<String, String> traceVariables;
    private final String s3Bucket;
    private final String s3Region;
    private final S3Client s3Client;
    private boolean alreadyClosed = false;

    /**
     * Crea un nuevo call tracer que escribirá a S3 cuando se llame close().
     *
     * @param traceVariables Map con todas las variables a escribir (headers SIP + metadata)
     */
    public CallTracer(Map<String, String> traceVariables) {
        this.traceVariables = traceVariables;

        // Leer configuración S3 de variables de entorno
        this.s3Bucket = System.getenv("S3_BUCKET_NAME");
        this.s3Region = System.getenv().getOrDefault("S3_REGION", "us-east-1");

        // Crear cliente S3 solo si bucket está configurado
        if (s3Bucket != null && !s3Bucket.isEmpty()) {
            this.s3Client = S3Client.builder()
                .region(Region.of(s3Region))
                .build();
            log.info("CallTracer initialized with S3: bucket={}, region={}", s3Bucket, s3Region);
        } else {
            this.s3Client = null;
            log.warn("S3_BUCKET_NAME not configured - traces will NOT be persisted");
        }

        String sipCallId = traceVariables.getOrDefault("sip_call_id", "unknown");
        log.info("CallTracer created for call_id={} ({} variables)", sipCallId, traceVariables.size());
    }

    /**
     * Retorna el SIP Call-ID para esta trace.
     */
    public String getCallId() {
        return traceVariables.getOrDefault("sip_call_id", "unknown");
    }

    /**
     * Escribe el trace a S3 cuando la llamada finaliza.
     * Llamado desde AbstractNovaS2SEventHandler.onComplete() o onError().
     */
    @Override
    public void close() {
        // Evitar doble escritura (onComplete + onError pueden llamar ambos)
        if (alreadyClosed) {
            log.debug("CallTracer already closed, skipping duplicate write");
            return;
        }
        alreadyClosed = true;

        String sipCallId = getCallId();
        log.info("CallTracer closing for call_id={}, writing to S3...", sipCallId);

        // Si S3 no está configurado, solo loguear y salir
        if (s3Client == null) {
            log.warn("S3 not configured, trace for call_id={} will not be persisted", sipCallId);
            return;
        }

        try {
            writeToS3();
            log.info("Successfully wrote trace to S3 for call_id={}", sipCallId);
        } catch (Exception e) {
            // Loguear error pero NO lanzar excepción - la llamada ya terminó
            log.error("Failed to write trace to S3 for call_id={}: {}", sipCallId, e.getMessage(), e);
        } finally {
            // Cerrar cliente S3
            if (s3Client != null) {
                try {
                    s3Client.close();
                } catch (Exception e) {
                    log.error("Error closing S3 client", e);
                }
            }
        }
    }

    /**
     * Escribe el trace a S3.
     * Formato: key:value por línea (plain text)
     * Path: s3://{bucket}/{client_id}/{sip_call_id}.txt
     */
    private void writeToS3() {
        String sipCallId = traceVariables.getOrDefault("sip_call_id", "unknown");
        String clientId = traceVariables.getOrDefault("client_id", "default");

        // Sanitizar Call-ID para usar como filename
        String sanitizedCallId = sanitizeFilename(sipCallId);

        // Construir S3 key: {client_id}/{sip_call_id}.txt
        String s3Key = String.format("%s/%s.txt", clientId, sanitizedCallId);

        // Generar contenido en formato key:value
        String content = generateTraceContent();

        // Crear request
        PutObjectRequest putRequest = PutObjectRequest.builder()
            .bucket(s3Bucket)
            .key(s3Key)
            .contentType("text/plain")
            .build();

        // Escribir a S3
        s3Client.putObject(putRequest, RequestBody.fromString(content, StandardCharsets.UTF_8));

        log.info("Wrote trace to S3: s3://{}/{} ({} bytes)", s3Bucket, s3Key, content.length());
    }

    /**
     * Genera el contenido del trace en formato key:value.
     * Formato:
     *   ani:573001234567
     *   call-id:abc123@provider.com
     *   client_id:keralty
     *   sip_call_id:abc123@provider.com
     *   x-client-name:jhonatan
     *   ...
     */
    private String generateTraceContent() {
        return traceVariables.entrySet().stream()
            .sorted(Map.Entry.comparingByKey()) // Ordenar alfabéticamente
            .map(entry -> String.format("%s:%s", entry.getKey(), entry.getValue()))
            .collect(Collectors.joining("\n"));
    }

    /**
     * Sanitiza un string para usar como filename en S3.
     * Reemplaza caracteres inválidos con underscore.
     */
    private String sanitizeFilename(String input) {
        if (input == null) {
            return "unknown";
        }
        // Reemplazar caracteres problemáticos para S3 keys
        return input.replaceAll("[<>:\"/\\\\|?*\\s]", "_");
    }
}
