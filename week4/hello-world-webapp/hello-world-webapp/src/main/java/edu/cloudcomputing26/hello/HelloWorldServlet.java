package edu.cloudcomputing26.hello;

import java.io.IOException;
import java.io.PrintWriter;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Minimal servlet that responds "Hello, world!" to any GET request.
 *
 * The @WebServlet annotation registers this class at the given URL
 * pattern without needing a web.xml deployment descriptor (this is the
 * modern, annotation-based style introduced in Servlet 3.0 and still the
 * standard approach under Jakarta Servlet 6.0 / Tomcat 10.1).
 */
@WebServlet(name = "HelloWorldServlet", urlPatterns = {"/hello"})
public class HelloWorldServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("text/plain;charset=UTF-8");

        try (PrintWriter out = response.getWriter()) {
            out.println("Hello, world!");
        }
    }
}
