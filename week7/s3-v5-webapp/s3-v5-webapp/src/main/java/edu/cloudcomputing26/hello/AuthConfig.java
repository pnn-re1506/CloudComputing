package edu.cloudcomputing26.hello;

import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

/**
 * Shared authentication configuration for this web app.
 *
 * This is s3-v4-webapp's AuthConfig with its token COMPARISON removed
 * and replaced by a call to the "CheckTokenIsValid" Lambda function
 * (see labs/check-token-is-valid-lambda/). The correct token itself no
 * longer exists anywhere in this project at all — not as a constant,
 * not in a config file. It lives only as that Lambda function's own
 * TOKEN environment variable. This class's only job now is: take
 * whatever token the caller is asking about, ask Lambda whether it's
 * correct, and return whatever Lambda says.
 *
 * Same shared-S3Client pattern as S3Config: one LambdaClient, built
 * once, reused for every invocation — building a new client per request
 * would work but wastes a connection setup on every single call.
 */
final class AuthConfig {

    /** EDIT ME: the name of the deployed Lambda function to call. */
    private static final String FUNCTION_NAME = "CheckTokenIsValid";

    /** Must match the region the function was created in (this course: ap-southeast-1). */
    private static final Region REGION = Region.AP_SOUTHEAST_1;

    private static final String HEADER_NAME = "Authorization";
    private static final String HEADER_PREFIX = "Bearer ";

    private static final LambdaClient CLIENT = LambdaClient.builder()
            .region(REGION)
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build();

    /**
     * Returns true if the request carries a token that Lambda confirms
     * is correct, false otherwise (missing header, wrong prefix, or
     * Lambda says no).
     */
    static boolean isAuthorized(HttpServletRequest request) {
        String header = request.getHeader(HEADER_NAME);
        if (header == null || !header.startsWith(HEADER_PREFIX)) {
            return false;
        }
        String providedToken = header.substring(HEADER_PREFIX.length());
        return isAuthorized(providedToken);
    }

    /**
     * Returns true if the given token is correct, by invoking
     * "CheckTokenIsValid" and reading its response. Used both by
     * AuthFilter (above, via the request-header overload) and by
     * LoginServlet (directly, with the token from the request body) —
     * one function call, one source of truth, no local copy of the
     * correct token anywhere in this class.
     */
    static boolean isAuthorized(String submittedToken) {
        // Lambda's Java runtime deserializes the invocation payload as
        // JSON into the handler's declared input type (a String) — so
        // the payload here must be a JSON string, i.e. submittedToken
        // wrapped in literal double quotes, not the bare characters.
        String jsonPayload = "\"" + submittedToken.replace("\"", "\\\"") + "\"";

        InvokeRequest invokeRequest = InvokeRequest.builder()
                .functionName(FUNCTION_NAME)
                .payload(SdkBytes.fromUtf8String(jsonPayload))
                .build();

        InvokeResponse invokeResponse = CLIENT.invoke(invokeRequest);

        // A Lambda invocation can "succeed" (no FunctionError) while the
        // function itself threw an exception — invokeResponse.functionError()
        // is non-null in that case. Treated here as "not authorized"
        // rather than throwing, so a broken/misconfigured Lambda function
        // fails closed (locks everyone out) instead of failing open
        // (lets everyone in) — worth saying explicitly in class which of
        // the two this is, and why it's the right choice for an auth check.
        if (invokeResponse.functionError() != null) {
            return false;
        }

        String responseJson = invokeResponse.payload().asString(StandardCharsets.UTF_8).trim();
        return "true".equals(responseJson);
    }

    private AuthConfig() {
        // no instances
    }
}
