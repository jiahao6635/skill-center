package com.iflytek.skillhub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
@ConfigurationProperties(prefix = "skillhub.publish.share-link")
public class SkillShareLinkProperties {

    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration requestTimeout = Duration.ofSeconds(30);
    private int maxRedirects = 5;
    private QoderWork qoderwork = new QoderWork();

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public int getMaxRedirects() {
        return maxRedirects;
    }

    public void setMaxRedirects(int maxRedirects) {
        this.maxRedirects = maxRedirects;
    }

    public QoderWork getQoderwork() {
        return qoderwork;
    }

    public void setQoderwork(QoderWork qoderwork) {
        this.qoderwork = qoderwork == null ? new QoderWork() : qoderwork;
    }

    public static class QoderWork {
        private boolean enabled = true;
        private String accessToken = "";
        private List<String> apiBases = new ArrayList<>(List.of(
                "https://openapi.qoder.com.cn",
                "https://openapi.qoder.sh",
                "https://api.qoder.com"
        ));
        private Set<String> allowedHosts = new LinkedHashSet<>(List.of(
                "qoder.com",
                "qoder.com.cn",
                "qoder.sh",
                "aliyuncs.com",
                "aliyun.com",
                "alicdn.com",
                "aliapp.org",
                "qiniucdn.com",
                "qiniucs.com",
                "myqcloud.com",
                "volces.com",
                "openstorage.cn"
        ));

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getAccessToken() {
            return accessToken;
        }

        public void setAccessToken(String accessToken) {
            this.accessToken = accessToken == null ? "" : accessToken;
        }

        public List<String> getApiBases() {
            return apiBases;
        }

        public void setApiBases(List<String> apiBases) {
            this.apiBases = apiBases == null ? new ArrayList<>() : new ArrayList<>(apiBases);
        }

        public Set<String> getAllowedHosts() {
            return allowedHosts;
        }

        public void setAllowedHosts(Set<String> allowedHosts) {
            this.allowedHosts = allowedHosts == null
                    ? new LinkedHashSet<>()
                    : new LinkedHashSet<>(allowedHosts);
        }
    }
}
