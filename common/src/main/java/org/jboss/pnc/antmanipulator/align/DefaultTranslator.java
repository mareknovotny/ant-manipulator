package org.jboss.pnc.antmanipulator.align;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import kong.unirest.GenericType;
import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import kong.unirest.jackson.JacksonObjectMapper;

/**
 * REST implementation of {@link Translator}, modelled on PME's {@code DefaultTranslator}: it POSTs the
 * coordinates to a Dependency-Analyzer-style endpoint and reads back each coordinate's aligned version.
 *
 * <p>
 * Like PME it uses Unirest with a Jackson object mapper, sends to {@code {restUrl}/lookup/maven}
 * (or {@code /lookup/maven/latest}), and partitions large coordinate lists into chunks so a big
 * project doesn't overwhelm the service in a single request.
 */
public final class DefaultTranslator implements Translator {

    private static final Logger logger = LoggerFactory.getLogger(DefaultTranslator.class);

    private static final String LOOKUP_GAVS = "lookup/maven";
    private static final String LOOKUP_LATEST = "lookup/maven/latest";

    private final String endpointUrl;
    private final Map<String, String> headers;
    private final String mode;
    private final Boolean brewPullActive;
    private final int chunkSize;

    /**
     * Back-compatible overload with no socket-timeout override (uses Unirest's default).
     *
     * @param restUrl base URL of the lookup service (with or without trailing slash)
     * @param headers extra request headers (e.g. auth); may be {@code null}
     * @param mode DA lookup mode (PERSISTENT/TEMPORARY/…), or {@code null} to omit
     * @param brewPullActive DA brew-pull flag, or {@code null} to omit
     * @param chunkSize max coordinates per request (PME defaults around 128)
     */
    public DefaultTranslator(
            String restUrl,
            Map<String, String> headers,
            String mode,
            Boolean brewPullActive,
            int chunkSize) {
        this(restUrl, headers, mode, brewPullActive, chunkSize, null);
    }

    /**
     * @param restUrl base URL of the lookup service (with or without trailing slash)
     * @param headers extra request headers (e.g. auth); may be {@code null}
     * @param mode DA lookup mode (PERSISTENT/TEMPORARY/…), or {@code null} to omit
     * @param brewPullActive DA brew-pull flag, or {@code null} to omit
     * @param chunkSize max coordinates per request (PME defaults around 128)
     * @param socketTimeoutSeconds read/socket timeout in seconds (PME's {@code restSocketTimeout}), or
     *        {@code null}/non-positive to keep Unirest's default; large batches against a slow DA can
     *        exceed the ~10s default
     */
    public DefaultTranslator(
            String restUrl,
            Map<String, String> headers,
            String mode,
            Boolean brewPullActive,
            int chunkSize,
            Integer socketTimeoutSeconds) {
        if (restUrl == null || restUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("restUrl must not be blank");
        }
        String base = restUrl.trim();
        this.endpointUrl = base.endsWith("/") ? base : base + "/";
        this.headers = headers == null ? new HashMap<>() : new HashMap<>(headers);
        this.mode = mode;
        this.brewPullActive = brewPullActive;
        this.chunkSize = chunkSize > 0 ? chunkSize : 128;
        configureUnirest(socketTimeoutSeconds);
    }

    private static void configureUnirest(Integer socketTimeoutSeconds) {
        // Skip nulls on the way out, ignore unknown fields on the way in — so the request stays minimal
        // and a server that adds response fields doesn't break us. Mirrors PME's tolerant mapper setup.
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        Unirest.config().setObjectMapper(new JacksonObjectMapper(mapper));
        // PME's restSocketTimeout: the read timeout for a lookup response. Only override when asked, so
        // the default (Unirest's ~10s) still applies for callers that pass nothing.
        if (socketTimeoutSeconds != null && socketTimeoutSeconds > 0) {
            Unirest.config().socketTimeout(socketTimeoutSeconds * 1000);
        }
    }

    @Override
    public Map<Gav, String> lookupVersions(List<Gav> coordinates) {
        final Map<Gav, String> result = new LinkedHashMap<>();
        final String url = endpointUrl + LOOKUP_GAVS;
        forEachChunk(coordinates, chunk -> {
            List<LookupResult> body = post(
                    url,
                    new LookupRequest(mode, brewPullActive, chunk),
                    new GenericType<List<LookupResult>>() {
                    });
            for (LookupResult r : body) {
                if (r.bestMatchVersion != null && !r.bestMatchVersion.trim().isEmpty()) {
                    result.put(r.toGav(), r.bestMatchVersion);
                }
            }
        });
        return result;
    }

    @Override
    public Map<Gav, String> lookupProjectVersions(List<Gav> coordinates) {
        final Map<Gav, String> result = new LinkedHashMap<>();
        final String url = endpointUrl + LOOKUP_LATEST;
        forEachChunk(coordinates, chunk -> {
            List<LatestResult> body = post(
                    url,
                    new LatestRequest(mode, chunk),
                    new GenericType<List<LatestResult>>() {
                    });
            for (LatestResult r : body) {
                if (r.latestVersion != null && !r.latestVersion.trim().isEmpty()) {
                    result.put(r.toGav(), r.latestVersion);
                }
            }
        });
        return result;
    }

    /** Split the distinct coordinates into chunks and hand each to {@code consumer}. */
    private void forEachChunk(List<Gav> coordinates, java.util.function.Consumer<List<Gav>> consumer) {
        final List<Gav> distinct = coordinates.stream().distinct().collect(Collectors.toList());
        for (int start = 0; start < distinct.size(); start += chunkSize) {
            consumer.accept(distinct.subList(start, Math.min(start + chunkSize, distinct.size())));
        }
    }

    /** POST one request body and parse the JSON array response, throwing on any non-2xx. */
    private <T> List<T> post(String url, Object request, GenericType<List<T>> type) {
        logger.debug("POST {}", url);
        HttpResponse<List<T>> response = Unirest.post(url)
                .header("accept", "application/json")
                .header("Content-Type", "application/json")
                .headers(headers)
                .body(request)
                .asObject(type);

        if (!response.isSuccess()) {
            throw new TranslatorException(
                    "Lookup at " + url + " failed with status "
                            + response.getStatus() + " " + response.getStatusText());
        }
        List<T> body = response.getBody();
        return body == null ? java.util.Collections.emptyList() : body;
    }

    /** Thrown when the lookup service returns an error or is unreachable. */
    public static final class TranslatorException extends RuntimeException {
        public TranslatorException(String message) {
            super(message);
        }

        public TranslatorException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
