package edu.cloudcomputing26.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;

/**
 * Replaces s3-v5-webapp's LoginServlet. The submitted token arrives as
 * the raw POST body (same as before — the static page's fetch() still
 * does `body: submittedToken`, no form parsing either way), gets
 * checked via AuthCheck, and the response is just a status code: 200
 * means "that token is correct," 401 means it isn't. Nothing is
 * remembered here — the page itself holds onto the token afterward and
 * resends it on every later request, exactly as in every version of
 * this app since s3-v3-webapp.
 */
public class LoginFunction implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        if (Cors.isPreflight(event)) {
            return Cors.preflightResponse();
        }

        String submittedToken = event.getBody();
        if (submittedToken == null) {
            submittedToken = "";
        }

        if (!AuthCheck.isAuthorized(submittedToken)) {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(401)
                    .withHeaders(Cors.HEADERS)
                    .withBody("Missing or incorrect token.")
                    .build();
        }

        return APIGatewayV2HTTPResponse.builder()
                .withStatusCode(200)
                .withHeaders(Cors.HEADERS)
                .withBody("OK")
                .build();
    }
}
