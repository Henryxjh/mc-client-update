package io.github.henryxjh.mcclientupdate.platform;

import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/** Loader-independent description of the operating system running Minecraft. */
public record RuntimePlatform(
        OperatingSystem operatingSystem,
        CpuArchitecture architecture,
        String rawOsName,
        String rawOsVersion,
        String rawOsArch) {

    public RuntimePlatform {
        Objects.requireNonNull(operatingSystem, "operatingSystem");
        Objects.requireNonNull(architecture, "architecture");
        rawOsName = Objects.requireNonNullElse(rawOsName, "");
        rawOsVersion = Objects.requireNonNullElse(rawOsVersion, "");
        rawOsArch = Objects.requireNonNullElse(rawOsArch, "");
    }

    public static RuntimePlatform detect() {
        return detect(System.getProperties(), hasAndroidRuntimeClass());
    }

    static RuntimePlatform detect(Properties properties, boolean androidRuntimeClassPresent) {
        String osName = properties.getProperty("os.name", "");
        String osVersion = properties.getProperty("os.version", "");
        String osArch = properties.getProperty("os.arch", "");

        OperatingSystem operatingSystem = detectOperatingSystem(
                osName,
                osVersion,
                properties.getProperty("java.runtime.name", ""),
                properties.getProperty("java.vm.name", ""),
                properties.getProperty("java.vendor", ""),
                androidRuntimeClassPresent);

        return new RuntimePlatform(
                operatingSystem,
                detectArchitecture(osArch),
                osName,
                osVersion,
                osArch);
    }

    /** For example: {@code android-aarch64}. */
    public String classifier() {
        return operatingSystem.id() + "-" + architecture.id();
    }

    public boolean isAndroid() {
        return operatingSystem == OperatingSystem.ANDROID;
    }

    private static OperatingSystem detectOperatingSystem(
            String osName,
            String osVersion,
            String runtimeName,
            String vmName,
            String vendor,
            boolean androidRuntimeClassPresent) {
        String androidEvidence = String.join(" ", osName, osVersion, runtimeName, vmName, vendor)
                .toLowerCase(Locale.ROOT);
        if (androidRuntimeClassPresent
                || androidEvidence.contains("android")
                || androidEvidence.contains("dalvik")) {
            return OperatingSystem.ANDROID;
        }

        String normalizedName = osName.toLowerCase(Locale.ROOT);
        if (normalizedName.contains("windows")) {
            return OperatingSystem.WINDOWS;
        }
        if (normalizedName.contains("mac") || normalizedName.contains("darwin")) {
            return OperatingSystem.MACOS;
        }
        if (normalizedName.contains("linux")) {
            return OperatingSystem.LINUX;
        }
        return OperatingSystem.UNKNOWN;
    }

    private static CpuArchitecture detectArchitecture(String osArch) {
        String normalized = osArch.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "amd64", "x86_64", "x64" -> CpuArchitecture.X86_64;
            case "x86", "i386", "i486", "i586", "i686" -> CpuArchitecture.X86_32;
            case "aarch64", "arm64", "armv8", "armv8l" -> CpuArchitecture.AARCH64;
            case "arm", "arm32", "armv7", "armv7l" -> CpuArchitecture.ARM32;
            case "riscv64" -> CpuArchitecture.RISCV64;
            case "loongarch64", "loong64" -> CpuArchitecture.LOONGARCH64;
            default -> CpuArchitecture.UNKNOWN;
        };
    }

    private static boolean hasAndroidRuntimeClass() {
        try {
            Class.forName("android.os.Build", false, ClassLoader.getSystemClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }
}
