#!/usr/bin/env bash
# 从零构建「酒馆」Android APK —— 纯手工工具链，不用 Gradle / 不用任何现成 App 工程。
# 流程：aapt2 资源 → javac → d8 → NDK clang 编译 JNI → zip 组装 → zipalign → apksigner 签名
set -euo pipefail

# 路径全部可由环境变量覆盖，便于在任何机器 / 云端复现构建：
#   STBUILD_DIR  = 素材目录（内含 st/ 酒馆源码、runtime/libnode/ Android Node 运行时）
#   APP_DIR/OUT_DIR = 工程与产物目录（默认脚本所在目录）
#   ANDROID_HOME = Android SDK 路径
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
APP=${APP_DIR:-$SCRIPT_DIR/app}
OUT=${OUT_DIR:-$SCRIPT_DIR}
BUILD=$APP/build
SCRATCH=${STBUILD_DIR:-$SCRIPT_DIR/.stbuild}

ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}
BT=$ANDROID_HOME/build-tools/34.0.0
ANDROID_JAR=$ANDROID_HOME/platforms/android-34/android.jar
AAPT2=$BT/aapt2
D8=$BT/d8
ZIPALIGN=$BT/zipalign
APKSIGNER=$BT/apksigner
AAPT=$BT/aapt
NDK=$ANDROID_HOME/ndk/26.3.11579264
LLVM=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
SYSROOT=$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib

ST=$SCRATCH/st
NODE_INC=$SCRATCH/runtime/libnode/include/node
PATCHES=$APP/patches

STAGE=$BUILD/stage
STORE=$APP/tavern.jks
STOREPASS=tavern-local-key

log() { echo; echo "==== $* ===="; }

log "0 / 前置检查"
for f in "$ANDROID_JAR" "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER"; do
    [ -e "$f" ] || { echo "缺少构建工具: $f"; exit 1; }
done
for abi in arm64-v8a x86_64; do
    [ -f "$SCRATCH/runtime/libnode/bin/$abi/libnode.so" ] || { echo "缺少 libnode.so ($abi)"; exit 1; }
done
[ -f "$ST/server.js" ] || { echo "缺少 SillyTavern 源码: $ST"; exit 1; }
[ -f "$APP/jni/node_bridge.c" ] || { echo "缺少 JNI 桥源码"; exit 1; }
PREBUILT=$(find "$ST/dist/_webpack" -path '*/output/lib.js' -type f | head -1)
[ -n "$PREBUILT" ] || { echo "缺少预编译前端 bundle lib.js"; exit 1; }
echo "工具链与素材就绪，预编译 lib.js: $PREBUILT"

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$BUILD/gen" "$BUILD/lib/arm64-v8a" "$BUILD/lib/x86_64" "$STAGE"

log "1 / 打包 SillyTavern 源码为内置 asset"
cp -a "$ST/." "$STAGE/"
rm -rf "$STAGE/.git" "$STAGE/.github" "$STAGE/.gitignore" "$STAGE/docker" \
       "$STAGE/tests" "$STAGE/test" "$STAGE/.npmrc" "$STAGE/Dockerfile" \
       "$STAGE/Colab" "$STAGE/*.md" 2>/dev/null || true
cp "$APP/assets/bootstrap.cjs" "$STAGE/bootstrap.cjs"

# 移动端关键适配：复用 npm 发布包自带的预编译前端 bundle，
# 并覆盖 webpack 中间件，彻底跳过 server.listen() 之前的 webpack 编译
# （在 Android 上极慢，且 webpack.config.js 顶层 await getVersion() 依赖 child_process）。
mkdir -p "$STAGE/prebuilt"
cp "$PREBUILT" "$STAGE/prebuilt/lib.js"
cp "$(dirname "$PREBUILT")/lib.js.LICENSE.txt" "$STAGE/prebuilt/" 2>/dev/null || true
cp "$PATCHES/webpack-serve.js" "$STAGE/src/middleware/webpack-serve.js"
rm -rf "$STAGE/dist/_webpack" "$STAGE/data/_webpack"
echo "已注入预编译 lib.js ($(du -h "$STAGE/prebuilt/lib.js" | cut -f1)) 并跳过 webpack 编译"

mkdir -p "$STAGE/data" "$STAGE/cache"
# 移动端不能让 ST 去拉起系统浏览器，并跳过每次内容检查
python3 - "$STAGE/config.yaml" <<'PY'
import pathlib, re, sys
p = pathlib.Path(sys.argv[1])
if p.exists():
    s = p.read_text(encoding='utf-8')
    s = re.sub(r'(browserLaunch:\s*\n\s*enabled:\s*)true', r'\1false', s)
    s = s.replace('skipContentCheck: false', 'skipContentCheck: true')
    p.write_text(s, encoding='utf-8')
    print('config.yaml: browserLaunch 已关闭, skipContentCheck 已开启')
PY
(
  cd "$STAGE"
  rm -f "$APP/assets/st.zip"
  zip -q -r -9 "$APP/assets/st.zip" .
)
echo "st.zip: $(du -h "$APP/assets/st.zip" | cut -f1), 文件数 $(unzip -l "$APP/assets/st.zip" | tail -1 | awk '{print $2}')"

log "2 / aapt2 编译并链接资源与 Manifest"
"$AAPT2" compile --dir "$APP/res" -o "$BUILD/res.zip"
"$AAPT2" link -o "$BUILD/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$APP/AndroidManifest.xml" \
    -R "$BUILD/res.zip" \
    --min-sdk-version 29 --target-sdk-version 34 \
    --auto-add-overlay \
    --java "$BUILD/gen"
echo "base.apk: $(du -h "$BUILD/base.apk" | cut -f1)"

log "3 / javac + d8 生成 dex"
find "$APP/src" -name '*.java' -printf '%p\n' > "$BUILD/sources.txt"
if [ -f "$BUILD/gen/com/xdu/tavern/R.java" ]; then echo "$BUILD/gen/com/xdu/tavern/R.java" >> "$BUILD/sources.txt"; fi
javac -source 17 -target 17 -encoding UTF-8 -nowarn \
    -classpath "$ANDROID_JAR" -d "$BUILD/classes" @"$BUILD/sources.txt" 2>&1 | grep -v 'bootstrap class path' || true
[ -f "$BUILD/classes/com/xdu/tavern/MainActivity.class" ] || { echo "javac 未产出 MainActivity.class"; exit 1; }
find "$BUILD/classes" -name '*.class' > "$BUILD/classlist.txt"
"$D8" --lib "$ANDROID_JAR" --min-api 29 --output "$BUILD/dex" \
    $(cat "$BUILD/classlist.txt" | tr '\n' ' ')
ls -lh "$BUILD/dex/classes.dex"

log "4 / NDK 编译自研 JNI 桥（纯 C + dlsym，不链接 libnode/C++ 运行库）"
for ABI in arm64-v8a x86_64; do
    case "$ABI" in
        arm64-v8a) TRIPLE=aarch64-linux-android29; SYSARCH=aarch64-linux-android ;;
        x86_64)    TRIPLE=x86_64-linux-android29;  SYSARCH=x86_64-linux-android ;;
    esac
    CC=$LLVM/${TRIPLE}-clang
    SYSLIB=$SYSROOT/$SYSARCH
    LIBNODE_ABI=$SCRATCH/runtime/libnode/bin/$ABI
    [ -x "$CC" ] || { echo "缺少编译器 $CC"; exit 1; }
    [ -f "$SYSLIB/libc++_shared.so" ] || { echo "缺少 $SYSLIB/libc++_shared.so"; exit 1; }
    # 纯 C 编译，只依赖 libc/liblog/libdl；绝不 -lnode / -lc++，
    # 否则会把 bionic 静态 TLS 实现拖进本库，Android 15/16 拒绝 dlopen。
    "$CC" -shared -fPIC -std=c11 -O2 \
        -o "$BUILD/lib/$ABI/libtavern.so" "$APP/jni/node_bridge.c" \
        -llog -ldl
    # 构建期硬断言：产物不得含任何 TLS 重定位（IE 模型会在真机上导致加载失败）
    if "$LLVM/llvm-readelf" -rW "$BUILD/lib/$ABI/libtavern.so" | grep -qiE 'TLS|TPREL|DTPMOD'; then
        echo "!!! $ABI 产物仍含 TLS 重定位，中止构建"; exit 1
    fi
    cp "$LIBNODE_ABI/libnode.so" "$BUILD/lib/$ABI/libnode.so"
    cp "$SYSLIB/libc++_shared.so" "$BUILD/lib/$ABI/libc++_shared.so"
    echo "-- $ABI：libtavern.so $(du -h "$BUILD/lib/$ABI/libtavern.so" | cut -f1)，无 TLS 重定位"
    "$LLVM/llvm-readelf" -d "$BUILD/lib/$ABI/libtavern.so" | grep NEEDED
done

log "5 / 组装 APK（classes.dex + lib + assets）"
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
zip -q -j "$BUILD/unsigned.apk" "$BUILD/dex/classes.dex"
(cd "$BUILD" && zip -q -r "$BUILD/unsigned.apk" lib)
# st.zip 已是压缩过的包，用 stored 方式放入，AssetManager 可直读且避免二次解压
(cd "$APP" && zip -q -0 -r "$BUILD/unsigned.apk" assets)
echo "unsigned.apk: $(du -h "$BUILD/unsigned.apk" | cut -f1)"

log "6 / 对齐"
"$ZIPALIGN" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

log "7 / 生成签名并签名"
if [ ! -f "$STORE" ]; then
  keytool -genkeypair -keystore "$STORE" -alias tavern -keyalg RSA -keysize 2048 \
      -validity 10950 -storepass "$STOREPASS" -keypass "$STOREPASS" \
      -dname "CN=Tavern, OU=XDU, O=Tavern, L=Xian, ST=Shaanxi, C=CN" >/dev/null 2>&1
fi
"$APKSIGNER" sign --ks "$STORE" --ks-key-alias tavern \
    --ks-pass "pass:$STOREPASS" --key-pass "pass:$STOREPASS" \
    --min-sdk-version 24 \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$OUT/SillyTavern.apk" "$BUILD/aligned.apk"
"$APKSIGNER" verify --min-sdk-version 29 --verbose "$OUT/SillyTavern.apk" | head -10

log "8 / 静态校验"
"$AAPT" dump badging "$OUT/SillyTavern.apk" | grep -E 'package:|sdkVersion:|targetSdkVersion:|uses-permission:|launchable-activity:|native-code:' || true
echo "--- 关键条目 ---"
unzip -l "$OUT/SillyTavern.apk" | grep -E 'classes.dex|lib/(arm64|x86_64)|assets/(st.zip|bootstrap.cjs)' | head -20
echo "--- 内置酒馆关键文件 ---"
python3 - "$APP/assets/st.zip" <<'PY'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1]); n = z.namelist()
for k in ('prebuilt/lib.js', 'src/middleware/webpack-serve.js', 'bootstrap.cjs', 'server.js', 'config.yaml'):
    print('  ', k, 'OK' if k in n else 'MISSING')
t = z.read('src/middleware/webpack-serve.js').decode()
print('   webpack 已跳过:', 'webpack compile skipped' in t)
c = z.read('config.yaml').decode()
print('   browserLaunch 已关闭:', 'enabled: false' in c)
PY
echo
echo "最终产物: $OUT/SillyTavern.apk  ($(du -h "$OUT/SillyTavern.apk" | cut -f1))"
echo BUILD_OK
