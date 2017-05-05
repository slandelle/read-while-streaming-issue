package io.gatling;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static java.util.Collections.*;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.UserStore;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.security.Password;

public class JettyServer implements AutoCloseable {

    public static final String USER = "user";
    public static final String PASSWORD = "pwd";
    private static final String ROLE = "user";

    private final Server server;

    public JettyServer(int port) throws Exception {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(port);
        server.addConnector(connector);

        HashLoginService loginService = new HashLoginService("MyRealm");
        UserStore userStore = new UserStore();
        userStore.addUser(USER, new Password(PASSWORD), new String[] { ROLE });
        loginService.setUserStore(userStore);

        ConstraintMapping mapping = new ConstraintMapping();
        Constraint constraint = new Constraint(Constraint.__BASIC_AUTH, ROLE);
        constraint.setAuthenticate(true);
        mapping.setConstraint(constraint);
        mapping.setPathSpec("/*");

        ConstraintSecurityHandler security = new ConstraintSecurityHandler();
        security.setConstraintMappings(singletonList(mapping), singleton(ROLE));
        security.setAuthenticator(new BasicAuthenticator());
        security.setLoginService(loginService);
        security.setHandler(new SimpleHandler());
        server.setHandler(security);
        server.start();

    }

    @Override
    public void close() throws Exception {
        server.stop();
    }

    private static class SimpleHandler extends AbstractHandler {

        public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {

            if (request.getHeader("X-401") != null) {
                response.setStatus(401);
                response.setContentLength(0);

            } else {
                byte[] bytes = new byte[10 * 1024];
                int contentLength = 0;
                int read = 0;
                do {
                    read = request.getInputStream().read(bytes);
                    if (read > 0) {
                        contentLength += read;
                    }
                } while (read >= 0);

                if (contentLength != request.getContentLength()) {
                    response.sendError(500, "Expected Content-Length of " + request.getContentLength() + " but actually received " + contentLength);
                } else {
                    response.setStatus(200);
                    response.addHeader("X-Auth", request.getHeader("Authorization"));
                    response.setIntHeader("X-" + CONTENT_LENGTH, request.getContentLength());
                }
            }
            r.setHandled(true);
        }
    }

    @SuppressWarnings("resource")
    public static void main(String[] args) throws Exception {
        new JettyServer(9999);
    }
}
