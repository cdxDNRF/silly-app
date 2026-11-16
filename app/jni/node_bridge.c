// 自研 JNI 桥（纯 C）。刻意不链接 libnode.so 与 C++ 运行库：
// 之前用 C++ + -lnode 编译会把 bionic 的静态 TLS/GWP-ASan 实现拖进本库，
// 产生 R_AARCH64_TLS_TPREL64（IE 模型）重定位，Android 15/16 在 dlopen 时
// 会直接拒绝（"TLS symbol ... using IE access model"）。
// 这里改为运行时 dlsym 调用已由 System.loadLibrary("node") 加载的 node::Start，
// 使本库只依赖 libc / liblog / libdl，不含任何 TLS 重定位。
#include <jni.h>
#include <android/log.h>

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

typedef int (*node_start_fn)(int argc, char **argv);

static char g_root[4096];

/* 先用 soname 命中已加载的 libnode.so；不行再从 /proc/self/maps 取绝对路径。 */
static void *load_libnode(void) {
    void *h = dlopen("libnode.so", RTLD_NOW | RTLD_NOLOAD);
    if (h) return h;

    FILE *f = fopen("/proc/self/maps", "r");
    if (f) {
        char line[2048];
        while (fgets(line, sizeof(line), f)) {
            if (!strstr(line, "libnode.so")) continue;
            char *s = strchr(line, '/');
            if (!s) continue;
            s[strcspn(s, "\n")] = '\0';
            h = dlopen(s, RTLD_NOW | RTLD_NOLOAD);
            if (h) break;
            h = dlopen(s, RTLD_NOW);
            if (h) break;
        }
        fclose(f);
    }
    return h;
}

JNIEXPORT jint JNICALL
Java_com_xdu_tavern_NodeBridge_start(JNIEnv *env, jclass clazz, jstring jroot) {
    (void)clazz;
    const char *p = (*env)->GetStringUTFChars(env, jroot, NULL);
    if (!p) return -1;
    snprintf(g_root, sizeof(g_root), "%s", p);
    (*env)->ReleaseStringUTFChars(env, jroot, p);

    /* Node 的 stdout/stderr 落到应用私有目录，便于应用内查看日志、排错。 */
    char log_path[4200];
    snprintf(log_path, sizeof(log_path), "%s/node.log", g_root);
    int fd = open(log_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd >= 0) {
        dup2(fd, STDOUT_FILENO);
        dup2(fd, STDERR_FILENO);
        if (fd > STDERR_FILENO) close(fd);
        setvbuf(stdout, NULL, _IONBF, 0);
        setvbuf(stderr, NULL, _IONBF, 0);
    } else {
        __android_log_print(ANDROID_LOG_ERROR, "tavern-jni",
                            "cannot open %s errno=%d", log_path, errno);
    }

    fprintf(stdout, "[jni] entered, root=%s\n", g_root);
    __android_log_print(ANDROID_LOG_INFO, "tavern-jni", "entered root=%s", g_root);

    char cache[4200], modules[4200], entry[4200];
    snprintf(cache, sizeof(cache), "%s/cache", g_root);
    snprintf(modules, sizeof(modules), "%s/node_modules", g_root);
    snprintf(entry, sizeof(entry), "%s/bootstrap.cjs", g_root);
    mkdir(cache, 0700);

    /* nodejs-mobile 的 cwd 默认是 "/"、没有 home/临时目录，这里全部显式指定。 */
    setenv("HOME", g_root, 1);
    setenv("TMPDIR", cache, 1);
    setenv("ST_ROOT", g_root, 1);
    setenv("NODE_ENV", "production", 1);
    setenv("PORT", "8000", 1);
    setenv("NODE_PATH", modules, 1);
    if (chdir(g_root) != 0) {
        fprintf(stderr, "[jni] chdir failed errno=%d\n", errno);
    }

    void *handle = load_libnode();
    if (!handle) {
        const char *err = dlerror();
        fprintf(stderr, "[jni] dlopen(libnode.so) failed: %s\n", err ? err : "unknown");
        __android_log_print(ANDROID_LOG_ERROR, "tavern-jni",
                            "dlopen libnode failed: %s", err ? err : "unknown");
        return -2;
    }

    node_start_fn node_start = NULL;
    *(void **)(&node_start) = dlsym(handle, "_ZN4node5StartEiPPc");
    if (!node_start) {
        const char *err = dlerror();
        fprintf(stderr, "[jni] dlsym(node::Start) failed: %s\n", err ? err : "unknown");
        return -3;
    }
    fprintf(stdout, "[jni] node::Start resolved via dlsym\n");

    /* libUV 要求 argv 字符串位于连续内存；末尾显式补 NULL。 */
    static char args_buffer[8192];
    char *cursor = args_buffer;
    char *argv0 = cursor;
    strcpy(cursor, "node");
    cursor += strlen("node") + 1;
    char *argv1 = cursor;
    strcpy(cursor, entry);
    char *argv[] = {argv0, argv1, NULL};

    fprintf(stdout, "[jni] calling node::Start entry=%s\n", entry);
    int rc = node_start(2, argv);
    fprintf(stdout, "[jni] node::Start returned rc=%d\n", rc);
    __android_log_print(ANDROID_LOG_WARN, "tavern-jni", "node exited rc=%d", rc);
    return (jint)rc;
}
