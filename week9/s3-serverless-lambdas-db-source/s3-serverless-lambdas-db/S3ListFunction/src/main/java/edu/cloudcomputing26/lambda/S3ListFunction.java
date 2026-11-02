package edu.cloudcomputing26.lambda;

import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Replaces s3-v5-webapp's S3ListServlet, same as every earlier version of
 * this function — plus one new step: for each object S3 returns, look up
 * whether a note exists for it in DynamoDB (table FileNotes, partition
 * key "fileKey") and include it in the JSON. A GetItem per object is
 * simplest to teach, at the cost of N DynamoDB calls for N objects — see
 * the lecture notes for the BatchGetItem alternative and when it's worth
 * the extra complexity.
 *
 * Lambda keeps this class "warm" between invocations on the same
 * execution environment, so both clients are built once in a static
 * initializer (same reasoning as S3Config's shared client in every
 * previous version of this app) rather than once per invocation.
 */
public class S3ListFunction implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    /** EDIT ME: must match the bucket name used by every other function in this app. */
    private static final String BUCKET_NAME = "cloud26-phucnhan-bucket";

    /** EDIT ME: must match the DynamoDB table name created for this lab. */
    private static final String TABLE_NAME = "FileNotes";

    private static final S3Client S3 = S3Client.builder()
            .region(Region.AP_SOUTHEAST_1)
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build();

    private static final DynamoDbClient DYNAMO_DB = DynamoDbClient.builder()
            .region(Region.AP_SOUTHEAST_1)
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build();

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

        try {
            ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                    .bucket(BUCKET_NAME)
                    .build();

            ListObjectsV2Response listResponse = S3.listObjectsV2(listRequest);

            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (S3Object object : listResponse.contents()) {
                if (!first) {
                    json.append(",");
                }
                first = false;
                json.append("{")
                        .append("\"key\":\"").append(escapeJson(object.key())).append("\",")
                        .append("\"size\":").append(object.size()).append(",")
                        .append("\"lastModified\":\"").append(object.lastModified()).append("\",")
                        .append("\"note\":\"").append(escapeJson(noteFor(object.key()))).append("\"")
                        .append("}");
            }
            json.append("]");

            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(200)
                    .withHeaders(Cors.headersWith("content-type", "application/json;charset=UTF-8"))
                    .withBody(json.toString())
                    .build();

        } catch (S3Exception e) {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(500)
                    .withHeaders(Cors.HEADERS)
                    .withBody("S3 error: " + e.awsErrorDetails().errorMessage()
                            + " (status " + e.statusCode() + ")")
                    .build();
        }
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Looks up this key's note in DynamoDB; "" if no item exists for it yet. */
    private static String noteFor(String key) {
        GetItemRequest getRequest = GetItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(Map.of("fileKey", AttributeValue.fromS(key)))
                .build();

        GetItemResponse getResponse = DYNAMO_DB.getItem(getRequest);
        if (!getResponse.hasItem()) {
            return "";
        }

        AttributeValue note = getResponse.item().get("note");
        return (note == null) ? "" : note.s();
    }
}
