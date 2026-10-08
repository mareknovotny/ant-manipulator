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
import java.util.List;
import java.util.Map;

import org.jboss.da.model.rest.GAV;
import org.jboss.pnc.antmanipulator.align.DefaultTranslator.TranslatorException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * Client tests for {@link DefaultTranslator} against a stubbed DA service (WireMock on a dynamic port).
 * These are the analogue of gradle-manipulator's {@code DAAlignmentServiceWiremockTest}: they pin the
 * request contract (path, the {@code artifacts} field name, {@code mode}/{@code brewPullActive}
 * inclusion, chunking, de-duplication) and the response contract (which field each endpoint reads,
 * filtering of blank versions, error propagation) without needing a live service.
 */
class DefaultTranslatorTest {

    private WireMockServer server;
    private String baseUrl;

    private static final GAV A = new GAV("org.acme", "widget", "1.0");
    private static final GAV B = new GAV("org.acme", "gadget", "2.0");
    private static final GAV C = new GAV("org.acme", "sprocket", "3.0");

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

    private DefaultTranslator translator() {
        return new DefaultTranslator(baseUrl, null, null, null, 128);
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
    void lookupVersionsReadsBestMatchVersionAndSendsArtifacts() {
        stubJson(
                "/lookup/maven",
                "[{\"groupId\":\"org.acme\",\"artifactId\":\"widget\",\"version\":\"1.0\","
                        + "\"bestMatchVersion\":\"1.0.redhat-00001\"}]");

        Map<GAV, String> result = translator().lookupVersions(Arrays.asList(A));

        assertThat(result).containsExactly(org.assertj.core.api.Assertions.entry(A, "1.0.redhat-00001"));
        // The coordinate set must be sent under "artifacts" (any other name yields a server-side 500).
        server.verify(
                postRequestedFor(urlEqualTo("/lookup/maven"))
                        .withRequestBody(matchingJsonPath("$.artifacts[0].groupId", equalTo("org.acme")))
                        .withRequestBody(matchingJsonPath("$.artifacts[0].artifactId", equalTo("widget")))
                        .withRequestBody(matchingJsonPath("$.artifacts[0].version", equalTo("1.0"))));
    }

    @Test
    void lookupProjectVersionsReadsLatestVersion() {
        stubJson(
                "/lookup/maven/latest",
                "[{\"groupId\":\"org.acme\",\"artifactId\":\"widget\",\"version\":\"1.0\","
                        + "\"latestVersion\":\"1.0.redhat-00005\"}]");

        Map<GAV, String> result = translator().lookupProjectVersions(Arrays.asList(A));

        assertThat(result).containsExactly(org.assertj.core.api.Assertions.entry(A, "1.0.redhat-00005"));
    }

    @Test
    void blankAndMissingVersionsAreFilteredOut() {
        stubJson(
                "/lookup/maven",
                "[{\"groupId\":\"org.acme\",\"artifactId\":\"widget\",\"version\":\"1.0\","
                        + "\"bestMatchVersion\":\"1.0.redhat-00001\"},"
                        + "{\"groupId\":\"org.acme\",\"artifactId\":\"gadget\",\"version\":\"2.0\","
                        + "\"bestMatchVersion\":\"  \"},"
                        + "{\"groupId\":\"org.acme\",\"artifactId\":\"sprocket\",\"version\":\"3.0\"}]");

        Map<GAV, String> result = translator().lookupVersions(Arrays.asList(A, B, C));

        // Only the coordinate with a non-blank bestMatchVersion survives.
        assertThat(result).containsOnlyKeys(A);
    }

    @Test
    void trailingSlashInBaseUrlIsHandled() {
        stubJson("/lookup/maven", "[]");

        new DefaultTranslator(baseUrl + "/", null, null, null, 128).lookupVersions(Arrays.asList(A));

        server.verify(postRequestedFor(urlEqualTo("/lookup/maven")));
    }

    @Test
    void defaultModeIsSentWhenNotConfigured() {
        stubJson("/lookup/maven", "[]");

        translator().lookupVersions(Arrays.asList(A));

        // When no mode is configured, the translator falls back to DEFAULT_MODE so the DA contract
        // (mode is @NonNull) is satisfied. brewPullActive defaults to false.
        server.verify(
                postRequestedFor(urlEqualTo("/lookup/maven"))
                        .withRequestBody(matchingJsonPath("$.mode", equalTo(DefaultTranslator.DEFAULT_MODE)))
                        .withRequestBody(matchingJsonPath("$.brewPullActive", equalTo("false"))));
    }

    @Test
    void modeAndBrewPullAreSentWhenConfigured() {
        stubJson("/lookup/maven", "[]");

        new DefaultTranslator(baseUrl, null, "PERSISTENT", Boolean.TRUE, 128)
                .lookupVersions(Arrays.asList(A));

        server.verify(
                postRequestedFor(urlEqualTo("/lookup/maven"))
                        .withRequestBody(matchingJsonPath("$.mode", equalTo("PERSISTENT")))
                        .withRequestBody(matchingJsonPath("$.brewPullActive", equalTo("true"))));
    }

    @Test
    void largeCoordinateListIsChunked() {
        stubJson("/lookup/maven", "[]");

        // Three distinct coordinates with chunk size 2 -> two POSTs (2 + 1).
        new DefaultTranslator(baseUrl, null, null, null, 2).lookupVersions(Arrays.asList(A, B, C));

        server.verify(2, postRequestedFor(urlEqualTo("/lookup/maven")));
    }

    @Test
    void duplicateCoordinatesAreDeduplicated() {
        stubJson("/lookup/maven", "[]");

        // Six entries but three distinct; chunk size 2 -> still only two POSTs.
        new DefaultTranslator(baseUrl, null, null, null, 2)
                .lookupVersions(Arrays.asList(A, A, B, B, C, C));

        server.verify(2, postRequestedFor(urlEqualTo("/lookup/maven")));
    }

    @Test
    void nonSuccessResponseThrowsTranslatorException() {
        server.stubFor(
                post(urlEqualTo("/lookup/maven"))
                        .willReturn(aResponse().withStatus(500).withStatusMessage("Server Error")));

        DefaultTranslator t = translator();
        List<GAV> gavs = Arrays.asList(A);
        assertThatThrownBy(() -> t.lookupVersions(gavs))
                .isInstanceOf(TranslatorException.class)
                .hasMessageContaining("500");
    }

    @Test
    void customHeadersAreSent() {
        stubJson("/lookup/maven", "[]");
        Map<String, String> headers = java.util.Collections.singletonMap("Authorization", "Bearer tok");

        new DefaultTranslator(baseUrl, headers, null, null, 128).lookupVersions(Arrays.asList(A));

        server.verify(
                postRequestedFor(urlEqualTo("/lookup/maven"))
                        .withHeader("Authorization", WireMock.equalTo("Bearer tok")));
    }

    @Test
    void blankBaseUrlIsRejected() {
        assertThatThrownBy(() -> new DefaultTranslator("  ", null, null, null, 128))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyResponseBodyYieldsEmptyMap() {
        stubJson("/lookup/maven", "[]");

        assertThat(translator().lookupVersions(Arrays.asList(A))).isEmpty();
        // containing() import kept meaningful: assert an artifacts array was still sent.
        server.verify(
                postRequestedFor(urlEqualTo("/lookup/maven"))
                        .withRequestBody(containing("artifacts")));
    }
}
