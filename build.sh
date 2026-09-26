#!/data/data/com.termux/files/usr/bin/sh
export JAVA_HOME=/data/data/com.termux/files/usr
export ANDROID_HOME=/data/data/com.termux/files/home/opt/android-sdk
export PATH=$JAVA_HOME/bin:$PATH
cd "$(dirname "$0")"
exec /data/data/com.termux/files/home/opt/gradle-8.9/bin/gradle assembleDebug --console=plain "$@"
