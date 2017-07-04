package i5.las2peer.connectors.nodeAdminConnector.handler;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;

import com.sun.net.httpserver.HttpExchange;

import i5.las2peer.connectors.nodeAdminConnector.NodeAdminConnector;
import i5.las2peer.p2p.Node;
import i5.las2peer.tools.SimpleTools;

public class DefaultHandler extends AbstractHandler {

	public DefaultHandler(NodeAdminConnector connector) {
		super(connector);
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		try {
			logger.info("Handler: " + getClass().getCanonicalName() + " Request-Path: "
					+ exchange.getRequestURI().getPath());
			exchange.getResponseHeaders().set("Server-Name", "las2peer " + getClass().getSimpleName());
			final Node node = connector.getNode();
			final Path path = Paths.get(exchange.getRequestURI().getPath());
			if (path.getNameCount() > 0) {
				String path0 = path.getName(0).toString();
				if (path0.equalsIgnoreCase("version")) {
					sendPlainResponse(exchange, getCoreVersion());
				} else if (path0.equalsIgnoreCase("nodeinfo")) {
					sendPlainResponse(exchange, node.getNodeInformation().toString());
				} else if (path0.equalsIgnoreCase("cpuload")) {
					sendPlainResponse(exchange, Integer.toString(getCPULoad(node)));
				} else if (path0.equalsIgnoreCase("netinfo")) {
					sendPlainResponse(exchange, SimpleTools.join(node.getOtherKnownNodes(), "\n"));
				} else if (path0.equalsIgnoreCase("favicon.ico")) {
					sendFaviconResponse(exchange);
				} else {
					sendStringResponse(exchange, HttpURLConnection.HTTP_NOT_FOUND, "text/plain", "404 (Not Found)\n");
				}
			} else {
				// perm redirect to frontend
				sendRedirect(exchange, FrontendHandler.STATUS_PATH, true);
			}
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Unknown connector error", e);
			sendPlainResponse(exchange, e.toString());
		}
	}

	private void sendFaviconResponse(HttpExchange exchange) {
		try {
			byte[] bytes = null;
			try {
				FileInputStream fis = new FileInputStream("etc/favicon.ico");
				bytes = SimpleTools.toByteArray(fis);
				fis.close();
			} catch (FileNotFoundException e) {
				// use fallback from classpath
				InputStream is = getClass().getResourceAsStream("/favicon.ico");
				if (is != null) {
					bytes = SimpleTools.toByteArray(is);
					is.close();
				}
			}
			OutputStream os = exchange.getResponseBody();
			if (bytes != null) {
				exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, bytes.length);
				os.write(bytes);
			} else {
				exchange.sendResponseHeaders(HttpURLConnection.HTTP_NOT_FOUND, NO_RESPONSE_BODY);
			}
			os.close();
		} catch (IOException e) {
			logger.log(Level.WARNING, e.toString());
		}
	}

}