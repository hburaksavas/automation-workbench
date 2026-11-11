package dev.testtr;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ScenarioIdentity {
    private ScenarioIdentity() {}
    public static String className(String source, String name) {
        try {
            byte[] identity = (source + "\n" + name).getBytes(StandardCharsets.UTF_8);
            return "Scenario_" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity)) + "Test";
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
