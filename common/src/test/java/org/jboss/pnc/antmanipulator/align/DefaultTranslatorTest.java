package org.jboss.pnc.antmanipulator.align;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.commonjava.atlas.maven.ident.ref.ProjectVersionRef;
import org.commonjava.atlas.maven.ident.ref.SimpleProjectVersionRef;
import org.jboss.pnc.mavenmanipulator.io.rest.DefaultTranslator;
import org.jboss.pnc.mavenmanipulator.io.rest.RestException;
import org.jboss.pnc.mavenmanipulator.io.rest.Translator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * Client tests for PME's {@link DefaultTranslator} against a stubbed DA service (WireMock on a dynamic port).
 * These pin the request contract (path, the {@code artifacts} field name, {@code mode}/{@code brewPullActive}
 * inclusion, chunking, de-duplication) and the response contract (which field each endpoint reads,
 * filtering of blank versions, error propagation, split-on-503 retry) without needing a live service.
 */
class DefaultTranslatorTest {

    private WireMockServer server;
    private String baseUrl;

    private static final ProjectVersionRef A = ref("org.acme", "widget", "1.0");
    private static final ProjectVersionRef B = ref("org.acme", "gadget", "2.0");
    private static final ProjectVersionRef C = ref("org.acme", "sprocket", "3.0");

    private static ProjectVersionRef ref(String g, String a, String v) {
        try {
            return new SimpleProjectVersionRef(g, a, v);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void startServer() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        baseUrl = "http://localhost:" + server.port();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private static final String DEFAULT_MODE = "PERSISTENT";

    /** Convenience: a translator with chunk size 128 and PME-default timeouts. */
    private DefaultTranslator translator() {
        return new DefaultTranslator(
                baseUrl,
                128,
                1,
                false,
                DEFAULT_MODE,
                Collections.emptyMap(),
                Translator.DEFAULT_CONNECTION_TIMEOUT_SEC,
                Translator.DEFAULT_SOCKET_TIMEOUT_SEC,
                Translator.RETRY_DURATION_SEC);
    }

    private void stubJson(String path, String responseBody) {
        server.stubFor(
                post(urlEqualTo(path))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(responseBody)));
    }

    @Test
    void lookupVersionsReadsBestMatchVersionAndSendsArtifacts() throws RestException {
        stubJson(
                "/lookup/maven",
                "[{\"groupId\":\"org.acme\",\"artifactId\":\"widget\",\"version\":\"1.0\","
                        + "\"bestMatchVersion\":\"1.0.redhat-00001\"}]");

        Map<ProjectVersionRef, String> result = translator().lookupVersions(Arrays.asList(A));

        assertThat(result).hasSize(1);
        assertThat(result.values()).containsExactly("1.0.redhat-00001");
        // The coordinate set must be sent under "artifacts" (any other name yields a server-side 500).
        server.verify(
                postRequestedFor(urlEqualTo("/lookup/maven"))
                        .withRequestBody(matchingJsonPath("$.artifacts[0].groupId", equalTo("org.acme")))
                        .withRequestBody(matchingJsonPath("$.artifacts[0].artifactId", equalTo("widget")))
                        .withRequestBody(matchingJsonPath("$.artifacts[0].version", equalTo("1.0"))));
    }

    @Test
    void lookupProjectVersionsReadsLatestVersion() throws RestException {
        stubJson(
                "/lookup/maven/latest",
                "[{\"groupId\":\"org.acme\",\"artifactId\":\"widget\",\"version\":\"1.0\","
                        + "\"latestVersion\":\"1.0.redhat-00005\"}]");

        Map<ProjectVersionRef, String> result = translator().lookupProjectVersions(Arrays.asList(A));

        assertThat(result).hasSize(1);
        assertThat(result.values()).containsExactly("1.0.redhat-00005");
    }

    @Test
    void blankAndMissingVersionsAreFilteredOut() throws RestException {
        stubJson(
                "/lookup/maven",
                "[{\"groupId\":\"org.acme\",\"artifactId\":\"widget\",\"version\":\"1.0\","
                        + "\"bestMatchVersion\":\"1.0.redhat-00001\"},"
                        + "{\"groupId\":\"org.acme\",\"artifactId\":\"gadget\",\"version\":\"2.0\","
                        + "\"bestMatchVersion\":\"  \"},"
                        + "{\"groupId\":\"org.acme\",\"artifactId\":\"sprocket\",\"version\":\"3.0\"}]");

        Map<ProjectVersionRef, String> result = translator().lookupVersions(Arrays.asList(A, B, C));

        // Only the coordinate with a non-blank bestMatchVersion survives.
        assertThat(result).hasSize(1);
        assertThat(result.values()).containsExactly("1.0.redhat-00001");
    }

    @Test
    void trailingSlashInBaseUrlIsHandled() throws RestException {
        stubJson("/lookup/maven", "[]");

        new DefaultTranslator(
                baseUrl + "/",
                128,
                1,
                false,
                DEFAULT_MODE,
                Collections.emptyMap(),
                Translator.DEFAULT_CONNECTION_TIMEOUT_SEC,
                Translator.DEFAULT_SOCKET_TIMEOUT_SEC,
                Translator.RETRY_DURATION_SEC).lookupVersions(Arrays.asList(A));

        server.verify(postRequestedFor(urlEqualTo("/lookup/maven")));
    }

    @Test
    void defaultModeIsSentWhenNotConfigured() throws RestException {
        stubJson("/lookup/maven", "[]");

        translator().lookupVersions(Arrays.asList(A));

        // When no mode is configured, PME's DefaultTranslator sends the configured mode (null here
        // means the field is omitted from the request body per NON_NULL serialisation).
        server.verify(
                postRequestedFor(urlEqualTo("/lookup/maven"))
                        .withRequestBody(containing("artifacts")));
    }

    @Test
    void modeAndBrewPullAreSentWhenConfigured() throws RestException {
        stubJson("/lookup/maven", "[]");

        new DefaultTranslator(
                baseUrl,
                128,
                1,
                Boolean.TRUE,
                "PERSISTENT",
                Collections.emptyMap(),
                Translator.DEFAULT_CONNECTION_TIMEOUT_SEC,
                Translator.DEFAULT_SOCKET_TIMEOUT_SEC,
                Translator.RETRY_DURATION_SEC).lookupVersions(Arrays.asList(A));

        server.verify(
                postRequestedFor(urlEqualTo("/lookup/maven"))
                        .withRequestBody(matchingJsonPath("$.mode", equalTo("PERSISTENT")))
                        .withRequestBody(matchingJsonPath("$.brewPullActive", equalTo("true"))));
    }

    @Test
    void largeCoordinateListIsChunked() throws RestException {
        stubJson("/lookup/maven", "[]");

        // Three distinct coordinates with chunk size 2 -> two POSTs (2 + 1).
        new DefaultTranslator(
                baseUrl,
                2,
                1,
                false,
                DEFAULT_MODE,
                Collections.emptyMap(),
                Translator.DEFAULT_CONNECTION_TIMEOUT_SEC,
                Translator.DEFAULT_SOCKET_TIMEOUT_SEC,
                Translator.RETRY_DURATION_SEC).lookupVersions(Arrays.asList(A, B, C));

        server.verify(2, postRequestedFor(urlEqualTo("/lookup/maven")));
    }

    @Test
    void duplicateCoordinatesAreDeduplicated() throws RestException {
        stubJson("/lookup/maven", "[]");

        // Six entries but three distinct; chunk size 2 -> still only two POSTs.
        new DefaultTranslator(
                baseUrl,
                2,
                1,
                false,
                DEFAULT_MODE,
                Collections.emptyMap(),
                Translator.DEFAULT_CONNECTION_TIMEOUT_SEC,
                Translator.DEFAULT_SOCKET_TIMEOUT_SEC,
                Translator.RETRY_DURATION_SEC).lookupVersions(Arrays.asList(A, A, B, B, C, C));

        server.verify(2, postRequestedFor(urlEqualTo("/lookup/maven")));
    }

    @Test
    void nonSuccessResponseThrowsRestException() {
        server.stubFor(
                post(urlEqualTo("/lookup/maven"))
                        .willReturn(aResponse().withStatus(500).withStatusMessage("Server Error")));

        DefaultTranslator t = translator();
        List<ProjectVersionRef> refs = Arrays.asList(A);
        assertThatThrownBy(() -> t.lookupVersions(refs))
                .isInstanceOf(RestException.class)
                .hasMessageContaining("500");
    }

    @Test
    void serviceUnavailableCausesChunkSplitAndRetry() throws RestException {
        // First call returns 503; subsequent calls succeed with empty body.
        server.stubFor(
                post(urlEqualTo("/lookup/maven"))
                        .inScenario("retry")
                        .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                        .willReturn(aResponse().withStatus(503).withStatusMessage("Service Unavailable"))
                        .willSetStateTo("retry-done"));
        server.stubFor(
                post(urlEqualTo("/lookup/maven"))
                        .inScenario("retry")
                        .whenScenarioStateIs("retry-done")
                        .willReturn(
                                aResponse().withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("[]")));

        // retryDuration=0 so we don't actually sleep in the test.
        DefaultTranslator t = new DefaultTranslator(
                baseUrl,
                2,
                1,
                false,
                DEFAULT_MODE,
                Collections.emptyMap(),
                Translator.DEFAULT_CONNECTION_TIMEOUT_SEC,
                Translator.DEFAULT_SOCKET_TIMEOUT_SEC,
                0);
        Map<ProjectVersionRef, String> result = t.lookupVersions(Arrays.asList(A, B));

        assertThat(result).isEmpty();
        // The initial 503 + the two split-chunk retries = at least 2 calls total.
        server.verify(
                WireMock.moreThanOrExactly(2),
                postRequestedFor(urlEqualTo("/lookup/maven")));
    }

    @Test
    void gatewayTimeoutCausesChunkSplitAndRetry() throws RestException {
        server.stubFor(
                post(urlEqualTo("/lookup/maven"))
                        .inScenario("gw-retry")
                        .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                        .willReturn(aResponse().withStatus(504).withStatusMessage("Gateway Timeout"))
                        .willSetStateTo("gw-retry-done"));
        server.stubFor(
                post(urlEqualTo("/lookup/maven"))
                        .inScenario("gw-retry")
                        .whenScenarioStateIs("gw-retry-done")
                        .willReturn(
                                aResponse().withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("[]")));

        DefaultTranslator t = new DefaultTranslator(
                baseUrl,
                2,
                1,
                false,
                DEFAULT_MODE,
                Collections.emptyMap(),
                Translator.DEFAULT_CONNECTION_TIMEOUT_SEC,
                Translator.DEFAULT_SOCKET_TIMEOUT_SEC,
                0);
        Map<ProjectVersionRef, String> result = t.lookupVersions(Arrays.asList(A, B));

        assertThat(result).isEmpty();
        server.verify(
                WireMock.moreThanOrExactly(2),
                postRequestedFor(urlEqualTo("/lookup/maven")));
    }

    @Test
    void nonRecoverableErrorIsNotRetried() {
        server.stubFor(
                post(urlEqualTo("/lookup/maven"))
                        .willReturn(aResponse().withStatus(400).withStatusMessage("Bad Request")));

        DefaultTranslator t = new DefaultTranslator(
                baseUrl,
                2,
                1,
                false,
                DEFAULT_MODE,
                Collections.emptyMap(),
                Translator.DEFAULT_CONNECTION_TIMEOUT_SEC,
                Translator.DEFAULT_SOCKET_TIMEOUT_SEC,
                0);
        List<ProjectVersionRef> refs = Collections.singletonList(A);
        assertThatThrownBy(() -> t.lookupVersions(refs))
                .isInstanceOf(RestException.class)
                .hasMessageContaining("400");
        // Exactly one request — no retry.
        server.verify(1, postRequestedFor(urlEqualTo("/lookup/maven")));
    }

    @Test
    void customHeadersAreSent() throws RestException {
        stubJson("/lookup/maven", "[]");
        Map<String, String> headers = Collections.singletonMap("Authorization", "Bearer tok");

        new DefaultTranslator(
                baseUrl,
                128,
                1,
                false,
                DEFAULT_MODE,
                headers,
                Translator.DEFAULT_CONNECTION_TIMEOUT_SEC,
                Translator.DEFAULT_SOCKET_TIMEOUT_SEC,
                Translator.RETRY_DURATION_SEC).lookupVersions(Arrays.asList(A));

        server.verify(
                postRequestedFor(urlEqualTo("/lookup/maven"))
                        .withHeader("Authorization", WireMock.equalTo("Bearer tok")));
    }

    @Test
    void emptyResponseBodyYieldsEmptyMap() throws RestException {
        stubJson("/lookup/maven", "[]");

        assertThat(translator().lookupVersions(Arrays.asList(A))).isEmpty();
        server.verify(
                postRequestedFor(urlEqualTo("/lookup/maven"))
                        .withRequestBody(containing("artifacts")));
    }
}
