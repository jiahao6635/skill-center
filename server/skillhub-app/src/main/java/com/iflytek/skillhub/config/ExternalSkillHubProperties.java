package com.iflytek.skillhub.config;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "skillhub.external-skills.skillhub-cn")
public class ExternalSkillHubProperties {
    private boolean enabled;
    private URI apiBaseUrl = URI.create("https://api.skillhub.cn");
    private Set<String> allowedDownloadHosts = new LinkedHashSet<>(
            Set.of("skillhub-1388575217.cos.accelerate.myqcloud.com"));
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration requestTimeout = Duration.ofSeconds(30);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public URI getApiBaseUrl() { return apiBaseUrl; }
    public void setApiBaseUrl(URI apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }
    public Set<String> getAllowedDownloadHosts() { return allowedDownloadHosts; }
    public void setAllowedDownloadHosts(Set<String> hosts) { this.allowedDownloadHosts = new LinkedHashSet<>(hosts); }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
}
