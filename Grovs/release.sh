#!/usr/bin/env bash
#
# Interactive release/publish helper for the Grovs Android SDK.
#
# Publishes to ONE target per run (to avoid confusion), reading the current
# published version from that target's registry and suggesting a version bump.
# Runs the existing Gradle publish tasks behind the scenes.
#
# Usage:
#   ./release.sh [--skip-tests] [-h|--help]
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

GRADLEW="./gradlew"
GRADLE_MODULE=":Grovs"
BUILD_GRADLE="Grovs/build.gradle.kts"
PROJECT_GRADLE_PROPS="gradle.properties"
USER_GRADLE_PROPS="$HOME/.gradle/gradle.properties"

CENTRAL_META_URL="https://repo1.maven.org/maven2/io/grovs/Grovs/maven-metadata.xml"
GITHUB_META_URL="https://maven.pkg.github.com/grovs-io/grovs-android-automation-app/io/grovs/grovs/maven-metadata.xml"
M2_LOCAL_DIR="$HOME/.m2/repository/io/grovs/Grovs"

SKIP_TESTS=false

# ----- pretty output --------------------------------------------------------
if [ -t 1 ] && command -v tput >/dev/null 2>&1 && [ "$(tput colors 2>/dev/null || echo 0)" -ge 8 ]; then
  BOLD="$(tput bold)"; DIM="$(tput dim)"; RED="$(tput setaf 1)"; GREEN="$(tput setaf 2)"
  YELLOW="$(tput setaf 3)"; CYAN="$(tput setaf 6)"; RESET="$(tput sgr0)"
else
  BOLD=""; DIM=""; RED=""; GREEN=""; YELLOW=""; CYAN=""; RESET=""
fi

info()  { printf '%s\n' "$*"; }
note()  { printf '%s%s%s\n' "$DIM" "$*" "$RESET"; }
warn()  { printf '%s⚠ %s%s\n' "$YELLOW" "$*" "$RESET" >&2; }
err()   { printf '%s✖ %s%s\n' "$RED" "$*" "$RESET" >&2; }
ok()    { printf '%s✓ %s%s\n' "$GREEN" "$*" "$RESET"; }
hdr()  { printf '\n%s%s%s\n' "$BOLD" "$*" "$RESET"; }

usage() {
  cat <<EOF
Interactive publish helper for the Grovs Android SDK.

Usage: ./release.sh [options]

Options:
  --skip-tests   Skip the pre-publish unit test run.
  -h, --help     Show this help.

Publishes to ONE target per run. Reads the current published version from the
selected target and suggests the next version. Runs the existing Gradle publish
tasks under the hood.
EOF
}

# ----- arg parsing ----------------------------------------------------------
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-tests) SKIP_TESTS=true ;;
    -h|--help) usage; exit 0 ;;
    *) err "Unknown option: $1"; usage; exit 2 ;;
  esac
  shift
done

# ----- helpers --------------------------------------------------------------

# Read a gradle property: env (ORG_GRADLE_PROJECT_<k> or bare) -> project props -> user props.
read_prop() {
  local key="$1" v
  local envname="ORG_GRADLE_PROJECT_${key}"
  v="${!envname:-}"; [ -n "$v" ] && { printf '%s' "$v"; return 0; }
  v="${!key:-}";     [ -n "$v" ] && { printf '%s' "$v"; return 0; }
  for f in "$PROJECT_GRADLE_PROPS" "$USER_GRADLE_PROPS"; do
    [ -f "$f" ] || continue
    v="$(grep -E "^[[:space:]]*${key}[[:space:]]*=" "$f" | head -1 || true)"
    if [ -n "$v" ]; then
      v="${v#*=}"
      v="$(printf '%s' "$v" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//')"
      printf '%s' "$v"; return 0
    fi
  done
  return 1
}

# Extract the release (or latest) version from a maven-metadata.xml on stdin.
parse_meta_version() {
  local xml; xml="$(cat)"
  local v
  v="$(printf '%s' "$xml" | grep -oE '<release>[^<]+</release>' | head -1 | sed -E 's/<\/?release>//g')"
  [ -z "$v" ] && v="$(printf '%s' "$xml" | grep -oE '<latest>[^<]+</latest>' | head -1 | sed -E 's/<\/?latest>//g')"
  [ -n "$v" ] && printf '%s' "$v"
}

# All published versions (one per line) from a maven-metadata.xml on stdin.
parse_meta_versions() {
  grep -oE '<version>[^<]+</version>' | sed -E 's/<\/?version>//g'
}

# Fallback: the libraryVersion constant in build.gradle.kts.
buildgradle_version() {
  [ -f "$BUILD_GRADLE" ] || return 1
  grep -oE '"[0-9]+\.[0-9]+\.[0-9]+"' "$BUILD_GRADLE" | head -1 | tr -d '"'
}

# Globals populated by detect_current_version:
CURRENT_VERSION=""
PUBLISHED_VERSIONS=""   # newline-separated
VERSION_SOURCE=""

detect_current_version() {
  local target="$1" meta="" ver=""
  case "$target" in
    central)
      if command -v curl >/dev/null 2>&1; then
        meta="$(curl -fsSL --max-time 20 "$CENTRAL_META_URL" 2>/dev/null || true)"
      fi
      ;;
    github)
      local gh_user gh_pass
      gh_user="$(read_prop GithubPackagesPrivateUsername || true)"
      gh_pass="$(read_prop GithubPackagesPrivatePassword || true)"
      if command -v curl >/dev/null 2>&1 && [ -n "$gh_user" ] && [ -n "$gh_pass" ]; then
        meta="$(curl -fsSL --max-time 20 -u "$gh_user:$gh_pass" "$GITHUB_META_URL" 2>/dev/null || true)"
      elif [ -z "$gh_user" ] || [ -z "$gh_pass" ]; then
        warn "GitHub Packages credentials not found; version lookup will fall back."
      fi
      ;;
    local)
      if [ -d "$M2_LOCAL_DIR" ]; then
        if [ -f "$M2_LOCAL_DIR/maven-metadata-local.xml" ]; then
          meta="$(cat "$M2_LOCAL_DIR/maven-metadata-local.xml")"
        fi
        # Also derive from version directories present on disk.
        PUBLISHED_VERSIONS="$(find "$M2_LOCAL_DIR" -maxdepth 1 -mindepth 1 -type d -exec basename {} \; 2>/dev/null || true)"
      fi
      ;;
  esac

  # Merge in the versions listed in metadata (if any).
  if [ -n "$meta" ]; then
    local vers; vers="$(printf '%s' "$meta" | parse_meta_versions || true)"
    PUBLISHED_VERSIONS="$(printf '%s\n%s\n' "$PUBLISHED_VERSIONS" "$vers" | grep -v '^$' | sort -u || true)"
  fi

  # Current version = highest *semver* published (ignore non-semver test artifacts
  # like 1.0.7-test-notifications). Fall back to the metadata <release>, then to
  # the build.gradle.kts constant.
  local highest_semver
  highest_semver="$(printf '%s\n' "$PUBLISHED_VERSIONS" | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -1 || true)"
  if [ -n "$highest_semver" ]; then
    ver="$highest_semver"
  elif [ -n "$meta" ]; then
    local rel; rel="$(printf '%s' "$meta" | parse_meta_version || true)"
    printf '%s' "$rel" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$' && ver="$rel"
  fi

  if [ -n "$ver" ]; then
    CURRENT_VERSION="$ver"; VERSION_SOURCE="registry"
  else
    CURRENT_VERSION="$(buildgradle_version || echo "0.0.0")"; VERSION_SOURCE="build.gradle.kts (fallback)"
    warn "No published semver version found for this target; using $CURRENT_VERSION from build.gradle.kts."
  fi
}

bump() {
  local ver="$1" kind="$2" M m p
  if ! printf '%s' "$ver" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    printf '%s' "$ver"; return 0
  fi
  M="${ver%%.*}"; p="${ver##*.}"; m="${ver#*.}"; m="${m%.*}"
  case "$kind" in
    patch) printf '%d.%d.%d' "$M" "$m" "$((p+1))" ;;
    minor) printf '%d.%d.0' "$M" "$((m+1))" ;;
    major) printf '%d.0.0' "$((M+1))" ;;
  esac
}

is_published() {
  local v="$1"
  printf '%s\n' "$PUBLISHED_VERSIONS" | grep -qxF "$v"
}

# ----- 1. select target -----------------------------------------------------
hdr "Grovs SDK — publish"
info "Select publish target:"
info "  ${BOLD}1${RESET}) Maven Central    ${DIM}(io.grovs:Grovs)${RESET}"
info "  ${BOLD}2${RESET}) Maven Local      ${DIM}(io.grovs:Grovs)${RESET}"
info "  ${BOLD}3${RESET}) GitHub Packages  ${DIM}(io.grovs:grovs)${RESET}"
printf '%s' "Target [1]: "
read -r target_choice || true
target_choice="${target_choice:-1}"

case "$target_choice" in
  1) TARGET="central"; TARGET_NAME="Maven Central";   COORDS="io.grovs:Grovs"; ARTIFACT_ID="Grovs"
     PUBLISH_TASK="publishMavenPublicationToMavenCentralRepository"; PASS_ARTIFACT=false ;;
  2) TARGET="local";   TARGET_NAME="Maven Local";     COORDS="io.grovs:Grovs"; ARTIFACT_ID="Grovs"
     PUBLISH_TASK="publishMavenPublicationToMavenLocal"; PASS_ARTIFACT=false ;;
  3) TARGET="github";  TARGET_NAME="GitHub Packages"; COORDS="io.grovs:grovs"; ARTIFACT_ID="grovs"
     PUBLISH_TASK="publishMavenPublicationToGithubPackagesPrivateRepository"; PASS_ARTIFACT=true ;;
  *) err "Invalid target: $target_choice"; exit 2 ;;
esac
ok "Target: $TARGET_NAME ($COORDS)"

# ----- 2. detect current version -------------------------------------------
hdr "Detecting current version on $TARGET_NAME..."
detect_current_version "$TARGET"
info "Current version: ${BOLD}$CURRENT_VERSION${RESET} ${DIM}(via $VERSION_SOURCE)${RESET}"

# ----- 3. choose new version -----------------------------------------------
V_PATCH="$(bump "$CURRENT_VERSION" patch)"
V_MINOR="$(bump "$CURRENT_VERSION" minor)"
V_MAJOR="$(bump "$CURRENT_VERSION" major)"

hdr "Select new version:"
info "  ${BOLD}1${RESET}) patch  -> $V_PATCH   ${DIM}[default]${RESET}"
info "  ${BOLD}2${RESET}) minor  -> $V_MINOR"
info "  ${BOLD}3${RESET}) major  -> $V_MAJOR"
info "  ${BOLD}4${RESET}) custom"
printf '%s' "Choice [1]: "
read -r ver_choice || true
ver_choice="${ver_choice:-1}"

case "$ver_choice" in
  1) NEW_VERSION="$V_PATCH" ;;
  2) NEW_VERSION="$V_MINOR" ;;
  3) NEW_VERSION="$V_MAJOR" ;;
  4) printf '%s' "Enter version (MAJOR.MINOR.PATCH[-qualifier]): "; read -r NEW_VERSION || true ;;
  *) err "Invalid choice: $ver_choice"; exit 2 ;;
esac

# Accept MAJOR.MINOR.PATCH with an optional -qualifier (e.g. 2.0.0-internal, 1.2.0-rc1, 1.1.0-SNAPSHOT).
if ! printf '%s' "$NEW_VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z][0-9A-Za-z.-]*)?$'; then
  err "Invalid version format: '$NEW_VERSION' (expected MAJOR.MINOR.PATCH, optionally -qualifier)."; exit 2
fi
if [ -n "$PUBLISHED_VERSIONS" ] && is_published "$NEW_VERSION"; then
  warn "Version $NEW_VERSION is already published on $TARGET_NAME."
  printf '%s' "Continue anyway? [y/N]: "; read -r ans || true
  case "${ans:-n}" in [Yy]*) ;; *) err "Aborted."; exit 1 ;; esac
fi
ok "New version: $NEW_VERSION"

# ----- 4. networkLogging ----------------------------------------------------
printf '%s' "Enable network logging in the published artifact? [y/N]: "
read -r nl || true
case "${nl:-n}" in [Yy]*) NETWORK_LOGGING=true ;; *) NETWORK_LOGGING=false ;; esac

# ----- 5. tests -------------------------------------------------------------
if [ "$SKIP_TESTS" = true ]; then
  warn "Skipping tests (--skip-tests)."
else
  # 'test' runs unit tests for ALL variants (debug + release). The published
  # artifact is the release AAR, so the release variant must be gated too.
  # Instrumented (androidTest) tests are excluded — they require a device.
  hdr "Running unit tests ($GRADLE_MODULE:test — all variants)..."
  if ! "$GRADLEW" "$GRADLE_MODULE:test"; then
    err "Tests failed — aborting release."; exit 1
  fi
  ok "Tests passed."
fi

# ----- 6. summary + confirm -------------------------------------------------
GRADLE_ARGS=("$GRADLE_MODULE:$PUBLISH_TASK" "-PlibraryVersion=$NEW_VERSION" "-PnetworkLogging=$NETWORK_LOGGING")
[ "$PASS_ARTIFACT" = true ] && GRADLE_ARGS+=("-PartifactId=$ARTIFACT_ID")

hdr "Release summary"
info "  Target        : $TARGET_NAME"
info "  Coordinates   : io.grovs:$ARTIFACT_ID"
info "  Version       : $CURRENT_VERSION  ->  ${BOLD}${GREEN}$NEW_VERSION${RESET}"
info "  networkLogging: $NETWORK_LOGGING"
info "  Command       : ${DIM}$GRADLEW ${GRADLE_ARGS[*]}${RESET}"
printf '\n%s' "Proceed with publish? [y/N]: "
read -r go || true
case "${go:-n}" in [Yy]*) ;; *) err "Aborted."; exit 1 ;; esac

# ----- 7. publish -----------------------------------------------------------
hdr "Publishing $ARTIFACT_ID $NEW_VERSION to $TARGET_NAME..."
if "$GRADLEW" "${GRADLE_ARGS[@]}"; then
  ok "Published io.grovs:$ARTIFACT_ID:$NEW_VERSION to $TARGET_NAME."
else
  code=$?
  err "Publish failed (exit $code)."
  exit "$code"
fi
