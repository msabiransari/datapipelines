import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The independent-process second binder for the #334 ownership witness (342-b): a separate
 * JVM, spawned through the single-file source launcher, that tries a wildcard bind — the
 * exact shape of the parallel fork that lost the ccba12bf gate its port — and records
 * whether the kernel let it. Exit status is always 0; the verdict is the file.
 */
public class PortContender {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        Path verdict = Path.of(args[1]);
        boolean took;
        try (ServerSocket socket = new ServerSocket(port)) {
            took = true;
        } catch (Exception bound) {
            took = false;
        }
        Files.writeString(verdict, "took=" + took);
    }
}
