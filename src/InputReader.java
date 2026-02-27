import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
 
// class to read the source code
public class InputReader {
    public static String ReadAll(String file) throws IOException {
        // read the source code from a file
        String sourceCode = new String(Files.readString(Paths.get(file)));
        return sourceCode;
    }
}
