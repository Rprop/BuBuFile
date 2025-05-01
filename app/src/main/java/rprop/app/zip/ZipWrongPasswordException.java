package rprop.app.zip;

import java.io.IOException;

final class ZipWrongPasswordException extends IOException {
    ZipWrongPasswordException() {
        super("wrong zip password");
    }
}
