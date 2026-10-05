package io.github.tmejs.opendds.types;

import Learning.PingRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeneratedTypeSupportTest {
    @Test
    void generatedPingRequestRetainsAssignedFields() {
        PingRequest request = new PingRequest();
        request.sequenceNumber = 1L;
        request.payload = "probe";

        assertEquals(1L, request.sequenceNumber);
        assertEquals("probe", request.payload);
    }

    @Test
    void generatedNativeTypeSupportLibraryCanBeLoaded() {
        String architecture = switch (System.getProperty("os.arch")) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> throw new IllegalStateException(
                    "Unsupported native library architecture: " + System.getProperty("os.arch"));
        };
        Path nativeLibrary = Path.of(
                "target", "classes", "native", "linux-" + architecture,
                System.mapLibraryName("learning_types"));

        System.load(nativeLibrary.toAbsolutePath().toString());
    }
}
