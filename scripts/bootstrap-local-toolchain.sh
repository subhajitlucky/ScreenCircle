#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly TOOLS_DIR="$ROOT_DIR/.tools"
readonly DOWNLOADS_DIR="$TOOLS_DIR/downloads"
readonly JDK_DIR="$TOOLS_DIR/jdk-17"
readonly GRADLE_VERSION="8.9"
readonly GRADLE_DIR="$TOOLS_DIR/gradle-$GRADLE_VERSION"
readonly SDK_DIR="$TOOLS_DIR/android-sdk"
readonly ANDROID_TOOLS_REVISION="16111833"
readonly ANDROID_TOOLS_ARCHIVE="commandlinetools-linux-${ANDROID_TOOLS_REVISION}_latest.zip"
readonly GRADLE_ARCHIVE="gradle-$GRADLE_VERSION-bin.zip"
readonly GRADLE_SHA256="d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab"
readonly CURL=(curl --fail --location --silent --show-error)
readonly DOWNLOAD_TMP="$DOWNLOADS_DIR/bootstrap.tmp"
readonly EXTRACT_TMP="$TOOLS_DIR/extract.tmp"

log() {
    printf '==> %s\n' "$*"
}

die() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

verify_checksum() {
    local archive="$1"
    local expected="$2"
    local algorithm="$3"
    local actual

    if [[ "$algorithm" == "sha256" ]]; then
        actual="$(sha256sum "$archive" | awk '{print $1}')"
    else
        actual="$(sha1sum "$archive" | awk '{print $1}')"
    fi

    [[ -n "$expected" ]] || die "Archive checksum unavailable for $archive"
    [[ "$actual" == "$expected" ]] || die "Checksum mismatch for $archive (expected $algorithm:$expected)"
}

download_verified() {
    local url="$1"
    local destination="$2"
    local checksum="$3"
    local algorithm="$4"

    [[ -n "$checksum" ]] || die "Archive checksum unavailable for $url"
    if [[ -f "$destination" ]]; then
        if verify_checksum "$destination" "$checksum" "$algorithm"; then
            return
        fi
        log "Discarding archive with an invalid checksum: $(basename "$destination")"
        rm -f "$destination"
    fi

    log "Downloading $(basename "$destination")"
    "${CURL[@]}" "$url" -o "$DOWNLOAD_TMP"
    verify_checksum "$DOWNLOAD_TMP" "$checksum" "$algorithm"
    mv "$DOWNLOAD_TMP" "$destination"
}

install_tar_archive() {
    local archive="$1"
    local destination="$2"

    if [[ -f "$destination/.screencircle-bootstrap-complete" ]]; then
        return
    fi

    rm -rf "$EXTRACT_TMP" "$destination"
    mkdir -p "$EXTRACT_TMP" "$destination"
    log "Installing $(basename "$archive")"
    tar -xzf "$archive" --strip-components=1 -C "$destination"
    touch "$destination/.screencircle-bootstrap-complete"
    rm -rf "$EXTRACT_TMP"
}

install_zip_archive() {
    local archive="$1"
    local destination="$2"
    local nested_directory="${3:-}"

    if [[ -f "$destination/.screencircle-bootstrap-complete" ]]; then
        return
    fi

    rm -rf "$EXTRACT_TMP" "$destination"
    mkdir -p "$EXTRACT_TMP" "$destination"
    log "Installing $(basename "$archive")"
    unzip -q "$archive" -d "$EXTRACT_TMP"
    if [[ -n "$nested_directory" ]]; then
        cp -R "$EXTRACT_TMP/$nested_directory/." "$destination/"
    else
        cp -R "$EXTRACT_TMP/." "$destination/"
    fi
    touch "$destination/.screencircle-bootstrap-complete"
    rm -rf "$EXTRACT_TMP"
}

find_adoptium_asset() {
    local metadata="$DOWNLOAD_TMP"
    local architecture="$1"

    "${CURL[@]}" \
        "https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=$architecture&heap_size=normal&image_type=jdk&jvm_impl=hotspot&os=linux&project=jdk" \
        -o "$metadata"
    python3 - "$metadata" "$architecture" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    assets = json.load(stream)

for asset in assets:
    package = asset.get("binary", {}).get("package", {})
    if package.get("name", "").endswith(".tar.gz"):
        checksum = package.get("checksum", "")
        link = package.get("link", "")
        if checksum and link:
            print(f"{link}\t{checksum}")
            break
else:
    print("Adoptium API returned no JDK archive with a checksum", file=sys.stderr)
    raise SystemExit(1)
PY
}

find_android_tools_asset() {
    local metadata="$DOWNLOAD_TMP"

    "${CURL[@]}" "https://dl.google.com/android/repository/repository2-1.xml" -o "$metadata"
    python3 - "$metadata" "$ANDROID_TOOLS_ARCHIVE" <<'PY'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
archive_name = sys.argv[2]
for archive in root.iter("archive"):
    if archive.findtext("host-os") != "linux":
        continue
    complete = archive.find("complete")
    if complete is None:
        continue
    url = complete.findtext("url", "")
    checksum = complete.findtext("checksum", "")
    if url == archive_name and checksum:
        print(f"https://dl.google.com/android/repository/{url}\t{checksum}")
        break
else:
    print(f"Android repository has no checksum for {archive_name}", file=sys.stderr)
    raise SystemExit(1)
PY
}

main() {
    [[ "$(uname -s)" == "Linux" ]] || die "This bootstrap script currently supports Linux only"

    local host_arch
    case "$(uname -m)" in
        x86_64) host_arch="x64" ;;
        aarch64 | arm64) host_arch="aarch64" ;;
        *) die "Unsupported CPU architecture: $(uname -m)" ;;
    esac

    for command in curl python3 tar unzip sha256sum sha1sum awk cp mkdir; do
        require_command "$command"
    done

    mkdir -p "$TOOLS_DIR" "$DOWNLOADS_DIR"
    rm -f "$DOWNLOAD_TMP"
    trap 'rm -f "$DOWNLOAD_TMP"; rm -rf "$EXTRACT_TMP"' EXIT

    local adoptium_asset jdk_url jdk_sha256 jdk_archive
    if [[ ! -x "$JDK_DIR/bin/java" ]]; then
        adoptium_asset="$(find_adoptium_asset "$host_arch")"
        IFS=$'\t' read -r jdk_url jdk_sha256 <<< "$adoptium_asset"
        jdk_archive="$DOWNLOADS_DIR/$(basename "$jdk_url")"
        download_verified "$jdk_url" "$jdk_archive" "$jdk_sha256" sha256
        install_tar_archive "$jdk_archive" "$JDK_DIR"
    fi

    local gradle_archive="$DOWNLOADS_DIR/$GRADLE_ARCHIVE"
    if [[ ! -f "$GRADLE_DIR/.screencircle-bootstrap-complete" ]]; then
        download_verified \
            "https://services.gradle.org/distributions/$GRADLE_ARCHIVE" \
            "$gradle_archive" "$GRADLE_SHA256" sha256
        install_zip_archive "$gradle_archive" "$GRADLE_DIR" "gradle-$GRADLE_VERSION"
    fi

    local android_asset android_url android_sha1
    if [[ ! -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]]; then
        android_asset="$(find_android_tools_asset)"
        IFS=$'\t' read -r android_url android_sha1 <<< "$android_asset"
        download_verified "$android_url" "$DOWNLOADS_DIR/$ANDROID_TOOLS_ARCHIVE" "$android_sha1" sha1
        install_zip_archive \
            "$DOWNLOADS_DIR/$ANDROID_TOOLS_ARCHIVE" \
            "$SDK_DIR/cmdline-tools/latest" \
            "cmdline-tools"
    fi

    printf 'sdk.dir=%s\n' "$SDK_DIR" > "$ROOT_DIR/local.properties"

    # shellcheck source=scripts/local-toolchain-env.sh
    source "$ROOT_DIR/scripts/local-toolchain-env.sh"

    log "Accepting Android SDK licenses"
    (yes || true) | sdkmanager --licenses >/dev/null
    log "Installing Android SDK platform 35, build-tools 35.0.0, and platform-tools"
    sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools" >/dev/null

    log "Generating the Gradle 8.9 wrapper"
    (cd "$ROOT_DIR" && "$GRADLE_DIR/bin/gradle" wrapper --gradle-version "$GRADLE_VERSION" --distribution-type bin)

    printf '\nToolchain bootstrap complete.\n'
    printf 'JAVA_HOME=%s\n' "$JAVA_HOME"
    printf 'ANDROID_SDK_ROOT=%s\n' "$ANDROID_SDK_ROOT"
    printf 'Gradle wrapper=%s\n' "$ROOT_DIR/gradlew"
    printf '\nThese variables only exist in this process. To use them in other shells run:\n'
    printf '  source %s\n' "$ROOT_DIR/scripts/local-toolchain-env.sh"
}

main "$@"
