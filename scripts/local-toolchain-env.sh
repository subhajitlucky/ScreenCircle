#!/usr/bin/env bash
# Sourceable helper that activates the user-local toolchain installed by
# scripts/bootstrap-local-toolchain.sh.
#
#   source scripts/local-toolchain-env.sh
#
# The bootstrap script exports these variables only inside its own process, so
# every new shell must source this file before running ./gradlew.

_local_env_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export SCREENCIRCLE_TOOLS_DIR="${SCREENCIRCLE_TOOLS_DIR:-$_local_env_root/.tools}"

export JAVA_HOME="$SCREENCIRCLE_TOOLS_DIR/jdk-17"
export ANDROID_HOME="$SCREENCIRCLE_TOOLS_DIR/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

unset _local_env_root

if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
    printf 'WARNING: %s does not exist. Run scripts/bootstrap-local-toolchain.sh first.\n' \
        "$JAVA_HOME" >&2
fi
