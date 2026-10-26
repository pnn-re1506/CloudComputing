package edu.cloudcomputing26.hello;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Checks a submitted token via AuthConfig.isAuthorized(String), which
 * now invokes the "CheckTokenIsValid" Lambda function under the hood
 * instead of comparing against a local constant (contrast with
 * s3-v3-webapp/s3-v4-webapp, where AuthConfig.TOKEN lived right here in
 * this project).
 *
 * POST /login — body: the token, as plain text.
 *
 * This servlet does NOT create a session, and does NOT remember
 * anything about the caller after this one request. It only answers
 * the single question "is this token correct?" (200) or not (401). The
 * client is responsible for holding on to the token afterward and
 * attaching it to every subsequent request itself — see AuthConfig and
 * index.html.
 */
@WebServlet(name = "LoginServlet", urlPatterns = {"/login"})
public class LoginServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        String submittedToken = new String(request.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8).trim();

        response.setContentType("text/plain;charset=UTF-8");

        if (AuthConfig.isAuthorized(submittedToken)) {
            response.getWriter().println("OK");
        } else {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().println("Incorrect token.");
        }
    }
}
