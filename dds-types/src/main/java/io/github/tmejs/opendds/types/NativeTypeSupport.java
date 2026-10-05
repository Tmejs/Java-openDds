package io.github.tmejs.opendds.types;

/** Loads the native library generated from this module's IDL before generated types are used. */
public final class NativeTypeSupport {
    private NativeTypeSupport() {
    }

    public static synchronized void load() {
        System.loadLibrary("learning_types");
    }
}
