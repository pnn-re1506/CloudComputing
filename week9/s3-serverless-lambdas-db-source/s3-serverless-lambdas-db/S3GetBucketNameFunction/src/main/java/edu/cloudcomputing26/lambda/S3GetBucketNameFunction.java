package edu.cloudcomputing26.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;

/**
 * Replaces s3-v5-webapp's S3GetBucketName servlet. Returns the
 * configured bucket name as plain text, after checking the caller's
 * token itself (there is no AuthFilter here — every function in this
 * app checks the token on its own, as the first line of its handler).
 */
public class S3GetBucketNameFunction implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    /** EDIT ME: must match the bucket name used by every other function in this app. */
    private static final String BUCKET_NAME = "cloud26-phucnhan-bucket";

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        if (Cors.isPreflight(event)) {
            return Cors.preflightResponse();
        }

        if (!AuthCheck.isAuthorized(event.getHeaders())) {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(401)
                    .withHeaders(Cors.HEADERS)
                    .withBody("Missing or incorrect token.")
                    .build();
        }

        return APIGatewayV2HTTPResponse.builder()
                .withStatusCode(200)
                .withHeaders(Cors.HEADERS)
                .withBody(BUCKET_NAME)
                .build();
    }
}
