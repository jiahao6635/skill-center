package com.iflytek.skillhub.service.sharelink;

import com.iflytek.skillhub.config.SkillPublishProperties;
import com.iflytek.skillhub.config.SkillShareLinkProperties;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteSkillPackageDownloaderTest {

    @Test
    void allowsConfiguredHttpsHostsOnly() {
        Set<String> allowed = Set.of("qoder.com", "aliyuncs.com");

        assertThat(RemoteSkillPackageDownloader.isAllowedUrl(URI.create("https://cdn.qoder.com/a.zip"), allowed))
                .isTrue();
        assertThat(RemoteSkillPackageDownloader.isAllowedUrl(URI.create("https://bucket.oss-cn-hangzhou.aliyuncs.com/a.zip"), allowed))
                .isTrue();
        assertThat(RemoteSkillPackageDownloader.isAllowedUrl(URI.create("http://cdn.qoder.com/a.zip"), allowed))
                .isFalse();
        assertThat(RemoteSkillPackageDownloader.isAllowedUrl(URI.create("https://evil.com/a.zip"), allowed))
                .isFalse();
        assertThat(RemoteSkillPackageDownloader.isAllowedUrl(URI.create("https://user:pass@cdn.qoder.com/a.zip"), allowed))
                .isFalse();
        assertThat(RemoteSkillPackageDownloader.isAllowedUrl(URI.create("https://127.0.0.1/a.zip"), allowed))
                .isFalse();
    }

    @Test
    void followsSingleRedirectToAllowedHost() {
        FakeHttpClient client = new FakeHttpClient();
        client.add(302, Map.of("location", List.of("https://cdn.qoder.com/final.zip")), new byte[0]);
        client.add(200, Map.of(), new byte[] {1, 2, 3});
        RemoteSkillPackageDownloader downloader = new RemoteSkillPackageDownloader(
                new SkillPublishProperties(),
                new SkillShareLinkProperties(),
                client,
                false
        );

        byte[] bytes = downloader.downloadZip(URI.create("https://openapi.qoder.com.cn/tmp"), Set.of("qoder.com", "qoder.com.cn"));

        assertThat(bytes).containsExactly(1, 2, 3);
        assertThat(client.sendCalls).isEqualTo(2);
    }

    @Test
    void rejectsRedirectToDisallowedHost() {
        FakeHttpClient client = new FakeHttpClient();
        client.add(302, Map.of("location", List.of("https://evil.com/final.zip")), new byte[0]);
        RemoteSkillPackageDownloader downloader = new RemoteSkillPackageDownloader(
                new SkillPublishProperties(),
                new SkillShareLinkProperties(),
                client,
                false
        );

        assertThatThrownBy(() -> downloader.downloadZip(
                URI.create("https://cdn.qoder.com/tmp"),
                Set.of("qoder.com")))
                .isInstanceOf(DomainBadRequestException.class)
                .extracting(ex -> ((DomainBadRequestException) ex).messageCode())
                .isEqualTo("error.skill.publish.shareLink.hostNotAllowed");
        assertThat(client.sendCalls).isEqualTo(1);
    }

    static class FakeHttpClient extends HttpClient {
        private final List<PreparedResponse> responses = new ArrayList<>();
        private int sendCalls;

        void add(int status, Map<String, List<String>> headers, byte[] body) {
            responses.add(new PreparedResponse(status, headers, body));
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.of(Duration.ofSeconds(5));
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return null;
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException {
            sendCalls++;
            PreparedResponse prepared = responses.removeFirst();
            @SuppressWarnings("unchecked")
            T body = (T) new ByteArrayInputStream(prepared.body);
            return new FakeResponse<>(request, prepared.status, prepared.headers, body);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }
    }

    record PreparedResponse(int status, Map<String, List<String>> headers, byte[] body) {}

    record FakeResponse<T>(HttpRequest request, int statusCode, Map<String, List<String>> headerMap, T body)
            implements HttpResponse<T> {
        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(headerMap, (name, value) -> true);
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }
    }
}
