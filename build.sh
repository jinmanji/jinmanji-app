#!/usr/bin/env sh
# 本地构建脚本：优先使用系统 gradle，其次使用仓库自带的 Gradle Wrapper。
# 可通过环境变量 GRADLE_CMD 指定 gradle 可执行文件。
cd "$(dirname "$0")" || exit 1

if [ -n "$GRADLE_CMD" ]; then
  GRADLE_BIN="$GRADLE_CMD"
elif command -v gradle >/dev/null 2>&1; then
  GRADLE_BIN="gradle"
elif [ -x "$HOME/opt/gradle-8.9/bin/gradle" ]; then
  GRADLE_BIN="$HOME/opt/gradle-8.9/bin/gradle"
else
  GRADLE_BIN="./gradlew"
fi

exec "$GRADLE_BIN" assembleDebug --console=plain "$@"
