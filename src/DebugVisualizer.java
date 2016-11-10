import java.awt.Color;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.List;
import java.util.Locale;

public class DebugVisualizer implements MyStrategy.Visualizer {

    final static String DEFAULT_HOST = "127.0.0.1";
    final static int DEFAULT_PORT = 13579;

    private Socket socket;
    private OutputStream outputStream;
    private List<String> queuedCommands = new ArrayList<>();

    public DebugVisualizer() {
        this(DEFAULT_HOST, DEFAULT_PORT);
    }

    public DebugVisualizer(String host, int port) {
        Locale.setDefault(new Locale("en", "US"));
        try {
            socket = new Socket(host, port);
            outputStream = socket.getOutputStream();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void stop() {
        try {
            outputStream.close();
            socket.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void drawBeforeScene() {
        sendCommand("begin pre");
        sendQueuedCommands();
        sendCommand("end pre");
    }

    public void drawAfterScene() {
        sendCommand("begin post");
        sendQueuedCommands();
        sendCommand("end post");
    }

    public void drawCircle(double x, double y, double r, Color color) {
        Formatter f = new Formatter();
        queueCommand(f.format("circle %1.1f %1.1f %1.1f %1.1f %1.1f %1.1f", x, y, r, (float) color.getRed() / 255, (float) color.getGreen() / 255, (float) color.getBlue() / 255).toString());
    }

    public void fillCircle(double x, double y, double r, Color color) {
        Formatter f = new Formatter();
        queueCommand(f.format("fill_circle %1.1f %1.1f %1.1f %1.1f %1.1f %1.1f", x, y, r, (float) color.getRed() / 255, (float) color.getGreen() / 255, (float) color.getBlue() / 255).toString());
    }

    public void drawRect(double x1, double y1, double x2, double y2, Color color) {
        Formatter f = new Formatter();
        queueCommand(f.format("rect %1.1f %1.1f %1.1f %1.1f %1.1f %1.1f %1.1f", x1, y1, x2, y2, (float) color.getRed() / 255, (float) color.getGreen() / 255, (float) color.getBlue() / 255).toString());
    }

    public void fillRect(double x1, double y1, double x2, double y2, Color color) {
        Formatter f = new Formatter();
        queueCommand(f.format("fill_rect %1.1f %1.1f %1.1f %1.1f %1.1f %1.1f %1.1f", x1, y1, x2, y2, (float) color.getRed() / 255, (float) color.getGreen() / 255, (float) color.getBlue() / 255).toString());
    }

    public void drawLine(double x1, double y1, double x2, double y2, Color color) {
        Formatter f = new Formatter();
        queueCommand(f.format("line %1.1f %1.1f %1.1f %1.1f %1.1f %1.1f %1.1f", x1, y1, x2, y2, (float) color.getRed() / 255, (float) color.getGreen() / 255, (float) color.getBlue() / 255).toString());
    }

    public void showText(double x, double y, String msg, Color color) {
        Formatter f = new Formatter();
        queueCommand(f.format("text %1.1f %1.1f %s %1.1f %1.1f %1.1f", x, y, msg, (float) color.getRed() / 255, (float) color.getGreen() / 255, (float) color.getBlue() / 255).toString());
    }

    private void sendCommand(String command) {
        try {
            outputStream.write((command + System.lineSeparator()).getBytes());
            outputStream.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void queueCommand(String command) {
        queuedCommands.add(command);
    }

    private void sendQueuedCommands() {
        for (String command : queuedCommands) {
            sendCommand(command);
        }
        queuedCommands.clear();
    }
}
