package edu.cloudcomputing26.lambda;

import java.util.Map;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;

/**
 * Every response needs CORS headers now, which no servlet in
 * s3-v5-webapp ever needed: the static page and this function no
 * longer share an origin. The page is served from Amplify's domain;
 * each Lambda Function URL has its own, completely different domain.
 * Without these headers the browser's fetch() calls would succeed at
 * the network level but the browser itself would discard the response.
 *
 * EDIT ME: "*" is fine for this lab (no cookies, no credentials mode,
 * and the token is sent explicitly in a header you control) — a real
 * production app would usually list its exact Amplify domain instead.
 */
final class Cors {

    static final Map<String, String> HEADERS = Map.of(
            "Access-Control-Allow-Origin", "*",
            "Access-Control-Allow-Headers", "authorization,content-type,x-filename",
            "Access-Control-Allow-Methods", "GET,POST,OPTIONS"
    );

    /** CORS headers plus one extra header (e.g. content-type) for a specific response. */
    static Map<String, String> headersWith(String key, String value) {
        Map<String, String> combined = new java.util.HashMap<>(HEADERS);
        combined.put(key, value);
        return combined;
    }

    /**
     * True if this request is a browser CORS preflight: an OPTIONS
     * request the browser sends automatically, BEFORE the real
     * GET/POST, whenever that real request carries a header the
     * browser considers "non-simple" (Authorization and X-Filename
     * both qualify). A preflight never carries your own custom
     * headers — including Authorization — so routing it into
     * AuthCheck.isAuthorized(...) like a normal request would always
     * fail, return 401, and make the browser abort before it ever
     * sends the real request at all. curl never triggers this, since
     * curl never sends a preflight — only a real browser fetch() does.
     */
    static boolean isPreflight(APIGatewayV2HTTPEvent event) {
        return "OPTIONS".equalsIgnoreCase(event.getRequestContext().getHttp().getMethod());
    }

    /** A plain 200 with just the CORS headers — the correct response to a preflight. */
    static APIGatewayV2HTTPResponse preflightResponse() {
        return APIGatewayV2HTTPResponse.builder()
                .withStatusCode(200)
                .withHeaders(HEADERS)
                .build();
    }

    private Cors() {
        // no instances
    }
}
