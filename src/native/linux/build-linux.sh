#!/usr/bin/env bash
# 构建 Linux 版原生宿主：src/native/linux/minetendo_host_linux.cpp → libminetendo.so
#
# 依赖（Debian/Ubuntu）：
#   sudo apt install build-essential libegl1-mesa-dev libgl1-mesa-dev
# 需要 JDK 的 include（只要头文件，不链接 libjvm）：
#   JAVA_HOME=/path/to/jdk21
#
# 用法：
#   ./build-linux.sh                # 产物放到 resources 与 run/minetendo 下
#   JAVA_HOME=/opt/jdk21 ./build-linux.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SRC="$HERE/minetendo_host_linux.cpp"
OUT_NAME="libminetendo.so"
OUT="$HERE/$OUT_NAME"
DEST_RES="$ROOT/src/main/resources/minetendo/native/linux-x86_64"
DEST_RUN="$ROOT/run/minetendo/native/linux-x86_64"

if [[ -z "${JAVA_HOME:-}" ]]; then
  # 常见位置兜底
  for cand in /usr/lib/jvm/*/ /opt/jdk*; do
    if [[ -f "$cand/include/jni.h" ]]; then JAVA_HOME="${cand%/}"; break; fi
  done
fi
if [[ -z "${JAVA_HOME:-}" || ! -f "$JAVA_HOME/include/jni.h" ]]; then
  echo "找不到 JDK（需要 include/jni.h）。请设置 JAVA_HOME。" >&2
  exit 1
fi

echo "== 编译 $OUT_NAME =="
echo "   JAVA_HOME=$JAVA_HOME"
echo "   GL/EGL: $(pkg-config --modversion egl 2>/dev/null || echo '?') / $(pkg-config --modversion gl 2>/dev/null || echo '?')"

g++ -shared -fPIC -O2 -std=c++17 \
    -o "$OUT" "$SRC" \
    -I "$JAVA_HOME/include" -I "$JAVA_HOME/include/linux" \
    -lEGL -lGL -ldl -lpthread

echo "   产物: $OUT ($(stat -c%s "$OUT") 字节)"

mkdir -p "$DEST_RES" "$DEST_RUN"
cp -f "$OUT" "$DEST_RES/$OUT_NAME"
cp -f "$OUT" "$DEST_RUN/$OUT_NAME"
echo "   已复制到:"
echo "     $DEST_RES/$OUT_NAME"
echo "     $DEST_RUN/$OUT_NAME"
echo "完成。启动客户端后 SessionHost 会自动从 jar 里解出这份 .so。"
