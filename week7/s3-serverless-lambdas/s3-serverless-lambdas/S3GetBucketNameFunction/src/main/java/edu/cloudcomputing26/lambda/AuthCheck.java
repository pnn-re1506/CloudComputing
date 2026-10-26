package edu.cloudcomputing26.lambda;

import java.nio.charset.StandardCharsets;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

/**
 * Same job as s3-v5-webapp's AuthConfig, moved here: take a submitted
 * token, ask the "CheckTokenIsValid" Lambda function whether it's
 * correct, and return whatever it says. There is no AuthFilter running
 * in front of this function the way there was in front of a servlet —
 * every Lambda function behind this app's Function URLs now calls this
 * itself, as the very first thing its handler does.
 *
 * This class is intentionally duplicated into every one of this app's
 * Lambda functions rather than shared from one library module — each
 * function deploys as its own independent .jar, with no shared
 * classpath between them, so "duplicated small file" is the actual
 * tradeoff of splitting one app into several separately deployed units.
 */
final class AuthCheck {

    private static final String FUNCTION_NAME = "CheckTokenIsValid";
    private static final Region REGION = Region.AP_SOUTHEAST_1;
    private static final String HEADER_NAME = "authorization";
    private static final String HEADER_PREFIX = "Bearer ";

    private static final LambdaClient CLIENT = LambdaClient.builder()
            .region(REGION)
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build();

    /**
     * Reads the Authorization header out of an API Gateway v2.0 /
     * Function URL event's header map (keys arrive lower-cased) and
     * checks it the same way AuthConfig.isAuthorized(HttpServletRequest)
     * did.
     */
    static boolean isAuthorized(java.util.Map<String, String> headers) {
        if (headers == null) {
            return false;
        }
        String header = headers.get(HEADER_NAME);
        if (header == null || !header.startsWith(HEADER_PREFIX)) {
            return false;
        }
        return isAuthorized(header.substring(HEADER_PREFIX.length()));
    }

    /** Identical logic to AuthConfig.isAuthorized(String) in s3-v5-webapp. */
    static boolean isAuthorized(String submittedToken) {
        String jsonPayload = "\"" + submittedToken.replace("\"", "\\\"") + "\"";

        InvokeRequest invokeRequest = InvokeRequest.builder()
                .functionName(FUNCTION_NAME)
                .payload(SdkBytes.fromUtf8String(jsonPayload))
                .build();

        InvokeResponse invokeResponse = CLIENT.invoke(invokeRequest);

        // Fail closed: a broken/misconfigured CheckTokenIsValid locks
        // everyone out rather than letting everyone in.
        if (invokeResponse.functionError() != null) {
            return false;
        }

        String responseJson = invokeResponse.payload().asString(StandardCharsets.UTF_8).trim();
        return "true".equals(responseJson);
    }

    private AuthCheck() {
        // no instances
    }
}
