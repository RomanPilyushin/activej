import io.activej.http.AsyncServlet;
import io.activej.http.IWebSocket.Message;
import io.activej.http.Servlet_Routing;
import io.activej.inject.annotation.Provides;
import io.activej.launchers.http.HttpServerLauncher;
import io.activej.reactor.Reactor;

public final class WebSocketPongServerExample extends HttpServerLauncher {

	//[START EXAMPLE]
	@Provides
	AsyncServlet servlet(Reactor reactor) {
		return Servlet_Routing.create(reactor)
				.mapWebSocket("/", webSocket -> webSocket.readMessage()
						.whenResult(message -> System.out.println("Received:" + message.getText()))
						.then(() -> webSocket.writeMessage(Message.text("Pong")))
						.whenComplete(webSocket::close));
	}
	//[END EXAMPLE]

	public static void main(String[] args) throws Exception {
		WebSocketPongServerExample launcher = new WebSocketPongServerExample();
		launcher.launch(args);
	}
}
