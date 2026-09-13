package net.neoforged.api.distmarker;

/** Compile-time stub of NeoForge's Dist enum (the real class ships with the loader at runtime). */
public enum Dist {
    CLIENT,
    DEDICATED_SERVER;

    public boolean isClient() {
        return this == CLIENT;
    }

    public boolean isDedicatedServer() {
        return this == DEDICATED_SERVER;
    }
}
