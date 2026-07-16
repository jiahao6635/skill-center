package com.iflytek.skillhub.auth.device;

import java.io.Serializable;

public class DeviceCodeData implements Serializable {
    private String deviceCode;
    private String userCode;
    private DeviceCodeStatus status;
    private String userId;
    private String clientId;
    private String clientName;
    private java.util.List<String> scopes;
    private String requestedNamespaceSlug;
    private Long namespaceId;
    private long lastPolledAt;
    private int pollIntervalSeconds = 5;
    private long expiresAtEpochMillis;
    private int expiresInDays = 90;

    public DeviceCodeData() {}

    public DeviceCodeData(String deviceCode, String userCode,
                          DeviceCodeStatus status, String userId) {
        this.deviceCode = deviceCode;
        this.userCode = userCode;
        this.status = status;
        this.userId = userId;
    }

    public DeviceCodeData(String deviceCode, String userCode, DeviceCodeStatus status, String userId,
                          String clientId, String clientName, java.util.List<String> scopes,
                          String requestedNamespaceSlug) {
        this(deviceCode, userCode, status, userId, clientId, clientName, scopes, requestedNamespaceSlug, 90);
    }

    public DeviceCodeData(String deviceCode, String userCode, DeviceCodeStatus status, String userId,
                          String clientId, String clientName, java.util.List<String> scopes,
                          String requestedNamespaceSlug, int expiresInDays) {
        this(deviceCode, userCode, status, userId);
        this.clientId = clientId;
        this.clientName = clientName;
        this.scopes = scopes;
        this.requestedNamespaceSlug = requestedNamespaceSlug;
        this.expiresInDays = expiresInDays;
        this.expiresAtEpochMillis = System.currentTimeMillis() + 900_000L;
    }

    public String getDeviceCode() { return deviceCode; }
    public String getUserCode() { return userCode; }
    public DeviceCodeStatus getStatus() { return status; }
    public void setStatus(DeviceCodeStatus status) { this.status = status; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getClientId() { return clientId; }
    public String getClientName() { return clientName; }
    public java.util.List<String> getScopes() { return scopes == null ? java.util.List.of() : scopes; }
    public String getRequestedNamespaceSlug() { return requestedNamespaceSlug; }
    public Long getNamespaceId() { return namespaceId; }
    public void setNamespaceId(Long namespaceId) { this.namespaceId = namespaceId; }
    public long getLastPolledAt() { return lastPolledAt; }
    public void setLastPolledAt(long lastPolledAt) { this.lastPolledAt = lastPolledAt; }
    public int getPollIntervalSeconds() { return pollIntervalSeconds; }
    public void setPollIntervalSeconds(int pollIntervalSeconds) { this.pollIntervalSeconds = pollIntervalSeconds; }
    public long getExpiresAtEpochMillis() { return expiresAtEpochMillis; }
    public int getExpiresInDays() { return expiresInDays; }
}
