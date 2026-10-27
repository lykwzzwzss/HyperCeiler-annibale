package com.sevtinge.hyperceiler.common.log;

/** Log adapter for the production missing-class guard in the host regression. */
public final class XposedLog {
    private XposedLog() {}
    public static void w(String tag, String message) {}
}
