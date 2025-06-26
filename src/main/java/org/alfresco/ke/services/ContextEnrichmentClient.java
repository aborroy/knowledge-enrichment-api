package org.alfresco.ke.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.alfresco.ke.common.OAuthTokenManager;
import org.alfresco.ke.config.AppProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.*;

/**
 * Minimal client for the Context‑Enrichment API.
 * <p>No retries, no automatic token refresh—just direct REST calls.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextEnrichmentClient {

    /** JSON field names that may carry a job/processing identifier. */
    private static final List<String> JOB_ID_FIELDS = List.of("processingId", "jobId", "id");

    private final RestTemplate restTemplate;
    private final AppProperties config;
    private final ObjectMapper objectMapper;
    private final OAuthTokenManager tokenManager;

    /**
     * Acquire a short‑lived OAuth2 bearer token for the Context‑Enrichment service.
     * The token is cached internally by {@link OAuthTokenManager} so subsequent calls are cheap.
     *
     * @return the access token as an opaque {@link String}
     */
    public String getAccessToken() {
        return tokenManager.getAccessToken(
                config.getContextEnrichment(),
                "environment_authorization",
                "context-enrichment"
        );
    }

    /**
     * Enumerate the NLP actions (e.g. entity extraction, sentiment, etc.) supported by the backend.
     *
     * @return a non‑null list of action names; empty when none are advertised
     */
    public List<String> getAvailableActions() {
        String url = buildUrl("/content/process/actions");
        ParameterizedTypeReference<List<String>> responseType = new ParameterizedTypeReference<>() {};

        ResponseEntity<List<String>> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders()),
                responseType
        );

        return response.getBody() != null ? response.getBody() : List.of();
    }

    /**
     * Ask the service for a presigned S3 URL that allows uploading a file of the given MIME type.
     *
     * @param contentType the MIME type of the file to be uploaded (e.g. <code>application/pdf</code>)
     * @return a map containing the URL and auxiliary form fields required by S3
     */
    public Map<String, Object> getPresignedUrl(String contentType) {
        URI uri = UriComponentsBuilder
                .fromUriString(buildUrl("/files/upload/presigned-url"))
                .queryParam("contentType", contentType)
                .build().toUri();
        ParameterizedTypeReference<Map<String, Object>> responseType = new ParameterizedTypeReference<>() {};

        return restTemplate.exchange(
                        uri.toString(),
                        HttpMethod.GET,
                        new HttpEntity<>(bearerHeaders()),
                        responseType)
                .getBody();

    }

    /**
     * Upload an in‑memory byte array to S3 via the provided presigned URL.
     *
     * @param presignedUrl the temporary, write‑enabled URL obtained via {@link #getPresignedUrl(String)}
     * @param data         raw file content
     * @param contentType  MIME type of <code>data</code>
     */
    public void uploadFileFromMemory(String presignedUrl,
                                     byte[] data,
                                     String contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));

        restTemplate.exchange(
                URI.create(presignedUrl),
                HttpMethod.PUT,
                new HttpEntity<>(data, headers),
                Void.class);
    }

    /**
     * Request processing of a previously uploaded file with one or more enrichment actions.
     *
     * @param objectKey S3 object key of the uploaded file
     * @param actions   list of action names retrieved from {@link #getAvailableActions()}; {@code null} or empty means all defaults
     * @return the processing/job identifier issued by the backend
     */
    public String processContent(String objectKey, List<String> actions) {

        String url = buildUrl("/content/process");

        Map<String, Object> body = new HashMap<>();
        body.put("objectKeys", List.of(objectKey));
        if (actions  != null && !actions.isEmpty()) body.put("actions", actions);

        HttpHeaders headers = bearerHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String response = restTemplate.exchange(
                        url,
                        HttpMethod.POST,
                        new HttpEntity<>(body, bearerHeaders()),
                        String.class)
                .getBody();

        return extractJobId(response);
    }

    /**
     * Retrieve the enrichment results once the job has completed.
     *
     * @param jobId the identifier returned by {@link #processContent(String, List)}
     * @return results JSON parsed into a {@code Map}
     */
    public Map<String, Object> getResults(String jobId) {
        String url = buildUrl("/content/process/" + jobId + "/results");
        ParameterizedTypeReference<Map<String, Object>> responseType = new ParameterizedTypeReference<>() {};

        return restTemplate.exchange(
                        url,
                        HttpMethod.GET,
                        new HttpEntity<>(bearerHeaders()),
                        responseType)
                .getBody();
    }

    /**
     * Attempt to derive a job identifier from the raw response body.
     * Handles both plain‑text IDs and JSON documents with common field names.
     *
     * @param body server response body
     * @return extracted job ID
     * @throws RuntimeException when no ID can be found or JSON is malformed
     */
    private String extractJobId(String body) {

        if (!body.trim().startsWith("{")) {
            return body.replace("\"", "").replace("'", "");
        }

        try {
            JsonNode json = objectMapper.readTree(body);
            return JOB_ID_FIELDS.stream()
                    .filter(json::hasNonNull)
                    .map(k -> json.get(k).asText())
                    .findFirst()
                    .orElseThrow(() ->
                            new RuntimeException(
                                    "No job ID found in response: " + body));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Invalid JSON: " + body, e);
        }
    }

    /**
     * Compose a fully‑qualified backend URL by appending <code>path</code> to the configured base URL.
     */
    private String buildUrl(String path) {
        return UriComponentsBuilder
                .fromUriString(config.getContextEnrichment().getApiUrl())
                .path(path)
                .toUriString();
    }

    /**
     * Construct headers that both accept JSON and carry the bearer token from {@link #getAccessToken()}.
     */
    private HttpHeaders bearerHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(getAccessToken());
        return headers;
    }

}