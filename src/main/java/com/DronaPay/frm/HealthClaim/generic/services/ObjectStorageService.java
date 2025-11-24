package com.DronaPay.frm.HealthClaim.generic.services;

import com.DronaPay.frm.HealthClaim.generic.storage.MinIOStorageProvider;
import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

@Slf4j
public class ObjectStorageService {

    private static final Map<String, StorageProvider> providerCache = new HashMap<>();

    /**
     * Get storage provider for a tenant
     * @param tenantId - tenant identifier
     * @return StorageProvider instance
     */
    public static StorageProvider getStorageProvider(String tenantId) throws Exception {
        // Check cache first
        if (providerCache.containsKey(tenantId)) {
            return providerCache.get(tenantId);
        }

        // Load tenant properties
        Properties props = ConfigurationService.getTenantProperties(tenantId);
        String providerType = props.getProperty("storage.provider", "minio");

        log.info("Initializing storage provider '{}' for tenant {}", providerType, tenantId);

        StorageProvider provider;
        switch (providerType.toLowerCase()) {
            case "minio":
                provider = new MinIOStorageProvider(props);
                break;
            case "s3":
                // Future: provider = new S3StorageProvider(props);
                throw new UnsupportedOperationException("S3 provider not yet implemented");
            case "azure":
                // Future: provider = new AzureBlobStorageProvider(props);
                throw new UnsupportedOperationException("Azure provider not yet implemented");
            default:
                throw new IllegalArgumentException("Unknown storage provider: " + providerType);
        }

        // Cache for reuse
        providerCache.put(tenantId, provider);
        return provider;
    }

    /**
     * Build storage path from template
     * @param pathPattern - pattern like "{tenantId}/{workflowKey}/{ticketId}/"
     * @param tenantId - tenant ID
     * @param workflowKey - workflow key (e.g., "HealthClaim")
     * @param ticketId - ticket ID
     * @param filename - document filename
     * @return full path
     */
    public static String buildStoragePath(String pathPattern, String tenantId,
                                          String workflowKey, String ticketId, String filename) {
        String path = pathPattern
                .replace("{tenantId}", tenantId)
                .replace("{workflowKey}", workflowKey)
                .replace("{ticketId}", String.valueOf(ticketId));

        return path + filename;
    }

    /**
     * Clear provider cache (for testing/hot-reload)
     */
    public static void clearCache() {
        providerCache.clear();
        log.info("Storage provider cache cleared");
    }
}