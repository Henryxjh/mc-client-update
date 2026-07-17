package io.github.henryxjh.mcclientupdate.platform;

/** Stable CPU architecture identifiers used by the update manifest. */
public enum CpuArchitecture {
    X86_64("x86_64"),
    X86_32("x86_32"),
    AARCH64("aarch64"),
    ARM32("arm32"),
    RISCV64("riscv64"),
    LOONGARCH64("loongarch64"),
    UNKNOWN("unknown");

    private final String id;

    CpuArchitecture(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
