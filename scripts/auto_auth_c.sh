#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

HOST=${HOST:-192.168.1.253}
SSH_PORT=${SSH_PORT:-8022}
SSH_USER=${SSH_USER:-root}
SSH_PASSWORD=${SSH_PASSWORD:-}
ADB_SERIAL_B=${ADB_SERIAL_B:-192.168.1.250:7777}

KEY_FILE=$(mktemp -t auth-keys.XXXXXX)
trap 'rm -f "$KEY_FILE"' EXIT

ensure_file() {
  if [ ! -f "$1" ]; then
    printf 'missing %s\n' "$1" >&2
    exit 1
  fi
}

ensure_file "$HOME/.android/adbkey.pub"

if [ -z "$SSH_PASSWORD" ]; then
  printf 'missing SSH_PASSWORD env var\n' >&2
  exit 1
fi

copy_to_app() {
  local src="$1"
  local name
  name=$(basename "$2")
  if [ ! -f "$src" ]; then
    return
  fi
  local tmp
  tmp=$(mktemp -t auth-cp.XXXXXX)
  cp "$src" "$tmp"
  adb -s "$ADB_SERIAL_B" push "$tmp" "/data/local/tmp/$name" >/dev/null
  adb -s "$ADB_SERIAL_B" shell "run-as com.example.scrcpyandroidfullproto sh -c 'cat /data/local/tmp/$name > files/.android/$name && chmod 600 files/.android/$name'"
  adb -s "$ADB_SERIAL_B" shell "rm /data/local/tmp/$name" >/dev/null 2>&1 || true
  rm -f "$tmp"
}

A_KEY_PATH="$HOME/.android/adbkey.pub"
B_KEY_PATH="/data/user/0/com.example.scrcpyandroidfullproto/files/.android/adbkey.pub"
A_KEY_CONTENT=""
B_KEY_CONTENT=""

A_KEY_CONTENT=$(cat "$A_KEY_PATH" | tr -d '\r')
if adb -s "$ADB_SERIAL_B" shell "run-as com.example.scrcpyandroidfullproto test -f files/.android/adbkey.pub" >/dev/null 2>&1; then
  B_KEY_CONTENT=$(adb -s "$ADB_SERIAL_B" shell "run-as com.example.scrcpyandroidfullproto cat files/.android/adbkey.pub" | tr -d '\r')
fi

{
  printf '%s\n' "$A_KEY_CONTENT"
  [ -n "$B_KEY_CONTENT" ] && printf '%s\n' "$B_KEY_CONTENT"
} | awk '!seen[$0]++' > "$KEY_FILE"

copy_to_app "$HOME/.android/adbkey" "files/.android/adbkey"
copy_to_app "$HOME/.android/adbkey.pub" "files/.android/adbkey.pub"

printf 'auth keys: %s\n' "$KEY_FILE"
printf 'copying %s keys to %s:%s\n' "$(wc -l < "$KEY_FILE" | tr -d ' ')" "$HOST" "$SSH_PORT"

sshpass -p "$SSH_PASSWORD" ssh -o StrictHostKeyChecking=no -p "$SSH_PORT" "$SSH_USER@$HOST" \
  "su -c 'cat > /data/misc/adb/adb_keys && chown system:shell /data/misc/adb/adb_keys && chmod 640 /data/misc/adb/adb_keys && restorecon -v /data/misc/adb/adb_keys >/dev/null 2>&1 && setprop ctl.restart adbd && sleep 1 && getprop init.svc.adbd'" < "$KEY_FILE"
