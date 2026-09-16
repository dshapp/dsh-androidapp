#!/usr/bin/env bash
#
# deploy.sh — 构建 DSH Phone 的 APK 并安装到已连接的 Android 设备。
#
#   ./deploy.sh                 构建 debug 包并安装到唯一连接的设备
#   ./deploy.sh -r              安装后顺便启动 App
#   ./deploy.sh -c              先 clean 再构建（改了资源/图标后建议加）
#   ./deploy.sh -v release      构建 release 变体（需自行配置签名）
#   ./deploy.sh -s 339d3f97     指定设备序列号（接了多台时必须指定）
#   ./deploy.sh -l              安装并启动后跟随 logcat（含 -r）
#   ./deploy.sh -h              查看帮助
#
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

PKG="ai.deepseek.dshphone"
ACTIVITY=".MainActivity"
VARIANT="debug"
DO_CLEAN=0
DO_RUN=0
DO_LOGCAT=0
SERIAL=""

# ── 颜色输出（非 TTY 时自动关闭）────────────────────────────────────────────
if [[ -t 1 ]]; then
  C_OK=$'\033[32m'; C_ERR=$'\033[31m'; C_INFO=$'\033[36m'; C_WARN=$'\033[33m'; C_OFF=$'\033[0m'
else
  C_OK=""; C_ERR=""; C_INFO=""; C_WARN=""; C_OFF=""
fi
info() { printf '%s==>%s %s\n' "$C_INFO" "$C_OFF" "$*"; }
ok()   { printf '%s ✓ %s %s\n' "$C_OK" "$C_OFF" "$*"; }
warn() { printf '%s ! %s %s\n' "$C_WARN" "$C_OFF" "$*" >&2; }
die()  { printf '%s ✗ %s %s\n' "$C_ERR" "$C_OFF" "$*" >&2; exit 1; }

usage() {
  sed -n '3,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 0
}

while getopts ":v:s:crlh" opt; do
  case "$opt" in
    v) VARIANT="$OPTARG" ;;
    s) SERIAL="$OPTARG" ;;
    c) DO_CLEAN=1 ;;
    r) DO_RUN=1 ;;
    l) DO_RUN=1; DO_LOGCAT=1 ;;
    h) usage ;;
    :) die "选项 -$OPTARG 需要一个参数" ;;
    *) die "未知选项 -${OPTARG}（用 -h 查看帮助）" ;;
  esac
done

# ── 1. 定位 JDK ────────────────────────────────────────────────────────────
# Gradle 8.9 不支持 JDK 22+（Android Studio 自带的 JBR 可能是 JDK 25），
# 所以这里优先挑选 17/21 这类受支持的版本。
jdk_major() {
  local home="$1" rel="$1/release" v=""
  [[ -r "$rel" ]] && v="$(sed -n 's/^JAVA_VERSION="\([0-9][0-9]*\).*/\1/p' "$rel" | head -1)"
  if [[ -z "$v" && -x "$home/bin/java" ]]; then
    v="$("$home/bin/java" -version 2>&1 | sed -n '1s/.*"\([0-9][0-9]*\).*/\1/p')"
  fi
  printf '%s' "${v:-0}"
}

pick_jdk() {
  local candidates=() home major
  [[ -n "${JAVA_HOME:-}" ]] && candidates+=("$JAVA_HOME")
  # 常见安装位置：手动安装、Homebrew、Gradle 下载的 toolchain、IDE 自带 JBR
  for home in "$HOME"/Library/Java/JavaVirtualMachines/*/Contents/Home \
              "$HOME"/Library/Java/*/Contents/Home \
              /Library/Java/JavaVirtualMachines/*/Contents/Home \
              /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
              /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
              "$HOME"/.gradle/jdks/*/Contents/Home \
              "$HOME"/.gradle/jdks/* \
              "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
    [[ -x "$home/bin/javac" ]] || continue
    major="$(jdk_major "$home")"
    # 受 Gradle 8.9 支持的范围
    if (( major >= 17 && major <= 21 )); then
      printf '%s' "$home"
      return 0
    fi
    candidates+=("$home")
  done
  # 没有理想版本时，退而求其次报告一个可用的，让用户自己决定
  for home in "${candidates[@]}"; do
    [[ -x "$home/bin/javac" ]] && { printf '%s' "$home"; return 0; }
  done
  return 1
}

JAVA_HOME="$(pick_jdk)" || die "找不到可用的 JDK。请安装 JDK 17 或 21，或手动设置 JAVA_HOME。"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
JDK_MAJOR="$(jdk_major "$JAVA_HOME")"
info "JDK $JDK_MAJOR — $JAVA_HOME"
if (( JDK_MAJOR < 17 || JDK_MAJOR > 21 )); then
  warn "JDK $JDK_MAJOR 可能不被当前 Gradle 支持（推荐 17 或 21），构建失败的话请换版本。"
fi

# ── 2. 定位 adb ────────────────────────────────────────────────────────────
find_adb() {
  local sdk c
  sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
  for c in "$sdk/platform-tools/adb" "$(command -v adb 2>/dev/null || true)"; do
    [[ -n "$c" && -x "$c" ]] && { printf '%s' "$c"; return 0; }
  done
  return 1
}
ADB="$(find_adb)" || die "找不到 adb。请安装 Android SDK Platform-Tools，或设置 ANDROID_SDK_ROOT。"

# ── 3. 选择设备 ────────────────────────────────────────────────────────────
"$ADB" start-server >/dev/null 2>&1 || true
DEVICE_LIST="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')"
DEVICE_COUNT="$(printf '%s\n' "$DEVICE_LIST" | grep -c . || true)"

if [[ -n "$SERIAL" ]]; then
  printf '%s\n' "$DEVICE_LIST" | grep -qx "$SERIAL" || die "设备 $SERIAL 未连接或未授权。"
elif (( DEVICE_COUNT == 0 )); then
  UNAUTH="$("$ADB" devices | awk 'NR>1 && $2=="unauthorized" {print $1}')"
  [[ -n "$UNAUTH" ]] && die "设备 $UNAUTH 未授权：请在手机上确认「允许 USB 调试」弹窗。"
  die "没有检测到设备。请插好 USB 线并在手机上开启「USB 调试」。"
elif (( DEVICE_COUNT > 1 )); then
  warn "检测到多台设备：$(printf '%s\n' "$DEVICE_LIST" | tr '\n' ' ')"
  die "请用 -s <序列号> 指定其中一台。"
else
  SERIAL="$DEVICE_LIST"
fi

MODEL="$("$ADB" -s "$SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
info "设备 $SERIAL${MODEL:+ ($MODEL)}"

# ── 4. 构建 ────────────────────────────────────────────────────────────────
# debug -> assembleDebug, release -> assembleRelease
CAP="$(printf '%s' "${VARIANT:0:1}" | tr '[:lower:]' '[:upper:]')${VARIANT:1}"
if (( DO_CLEAN )); then
  info "清理旧的构建产物…"
  ./gradlew clean --console=plain -q
fi
info "构建 assemble$CAP …"
./gradlew "assemble$CAP" --console=plain

# ── 5. 找到 APK ────────────────────────────────────────────────────────────
APK="$(ls -t app/build/outputs/apk/"$VARIANT"/*.apk 2>/dev/null | head -1 || true)"
[[ -n "$APK" && -f "$APK" ]] || die "构建产物不存在：app/build/outputs/apk/$VARIANT/*.apk"
ok "APK $APK ($(du -h "$APK" | cut -f1))"

# ── 6. 安装 ────────────────────────────────────────────────────────────────
# -r 覆盖安装，-d 允许版本号回退（本地调试常见）
info "安装到 $SERIAL …"
if ! OUTPUT="$("$ADB" -s "$SERIAL" install -r -d "$APK" 2>&1)"; then
  printf '%s\n' "$OUTPUT" >&2
  # 签名不一致是最常见的失败原因，给出明确的下一步
  if grep -q 'INSTALL_FAILED_UPDATE_INCOMPATIBLE\|signatures do not match' <<<"$OUTPUT"; then
    die "已安装的版本签名不同。先卸载：$ADB -s $SERIAL uninstall $PKG"
  fi
  die "安装失败（详见上方输出）。"
fi
printf '%s\n' "$OUTPUT" | grep -q 'Success' || { printf '%s\n' "$OUTPUT" >&2; die "安装未返回 Success。"; }
ok "安装成功"

# ── 7. 可选：启动 / 跟随日志 ───────────────────────────────────────────────
if (( DO_RUN )); then
  info "启动 $PKG …"
  "$ADB" -s "$SERIAL" shell am start -n "$PKG/$ACTIVITY" >/dev/null
  ok "已启动"
fi

if (( DO_LOGCAT )); then
  # 只看本 App 的日志：拿到 pid 后按进程过滤
  sleep 1
  PID="$("$ADB" -s "$SERIAL" shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}')"
  info "跟随 logcat（Ctrl-C 退出）…"
  if [[ -n "$PID" ]]; then
    "$ADB" -s "$SERIAL" logcat --pid="$PID"
  else
    warn "拿不到进程号，改为显示全部日志。"
    "$ADB" -s "$SERIAL" logcat
  fi
fi
