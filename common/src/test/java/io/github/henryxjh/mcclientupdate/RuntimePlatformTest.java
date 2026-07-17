package io.github.henryxjh.mcclientupdate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import org.junit.jupiter.api.Test;

class RuntimePlatformTest {
    @Test
    void detectsAndroidReportedAsLinux() {
        Properties properties = properties("Linux", "Android-16", "aarch64");

        RuntimePlatform platform = RuntimePlatform.detect(properties, false);

        assertEquals(OperatingSystem.ANDROID, platform.operatingSystem());
        assertEquals(CpuArchitecture.AARCH64, platform.architecture());
        assertEquals("android-aarch64", platform.classifier());
        assertTrue(platform.isAndroid());
    }

    @Test
    void detectsAndroidFromRuntimeClassWhenPropertiesLookLikeLinux() {
        Properties properties = properties("Linux", "6.1.0", "arm64");

        RuntimePlatform platform = RuntimePlatform.detect(properties, true);

        assertEquals("android-aarch64", platform.classifier());
    }

    @Test
    void detectsDesktopPlatformsAndArchitectureAliases() {
        assertEquals(
                "windows-x86_64",
                RuntimePlatform.detect(properties("Windows 11", "10.0", "amd64"), false).classifier());
        assertEquals(
                "linux-x86_64",
                RuntimePlatform.detect(properties("Linux", "6.12", "x86_64"), false).classifier());
        assertEquals(
                "macos-aarch64",
                RuntimePlatform.detect(properties("Mac OS X", "15.0", "arm64"), false).classifier());
    }

    @Test
    void producesLoaderSpecificTarget() {
        RuntimePlatform platform = RuntimePlatform.detect(
                properties("Linux", "Android-16", "aarch64"), false);

        assertEquals("neoforge-android-aarch64", new UpdateTarget("NeoForge", platform).classifier());
    }

    private static Properties properties(String osName, String osVersion, String osArch) {
        Properties properties = new Properties();
        properties.setProperty("os.name", osName);
        properties.setProperty("os.version", osVersion);
        properties.setProperty("os.arch", osArch);
        return properties;
    }
}
