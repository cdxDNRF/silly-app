package com.xdu.tavern;

/** JNI 桥：在同一进程内启动 Node.js，运行 SillyTavern 服务端。 */
public final class NodeBridge {
    private static boolean loaded = false;
    private static String loadError = null;

    /** 显式加载 native 库并把失败原因返回给调用方（写进 boot.log，便于真机排错）。 */
    public static synchronized String ensureLoaded() {
        if (loaded) return null;
        if (loadError != null) return loadError;
        try {
            System.loadLibrary("node");
            System.loadLibrary("tavern");
            loaded = true;
            return null;
        } catch (Throwable t) {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            loadError = sw.toString();
            return loadError;
        }
    }

    /** 阻塞调用，内部运行 uv 事件循环，必须在子线程调用。 */
    public static native int start(String root);

    private NodeBridge() {}
}
