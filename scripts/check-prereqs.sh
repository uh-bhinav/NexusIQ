#!/usr/bin/env bash
# NexusIQ prerequisite check.
#
#   ./scripts/check-prereqs.sh infra   what the Docker stack needs (Phase 0)
#   ./scripts/check-prereqs.sh all     everything needed to build the services
#
# Fails loudly with install instructions rather than letting a later step break
# in a confusing way.

set -uo pipefail

MODE="${1:-all}"
FAILED=0
NOTE=""

RED='\033[0;31m'; GRN='\033[0;32m'; YLW='\033[0;33m'; DIM='\033[2m'; NC='\033[0m'

ok()   { printf "  ${GRN}✓${NC} %-14s %s\n" "$1" "$2"; }
bad()  { printf "  ${RED}✗${NC} %-14s %s\n" "$1" "$2"; FAILED=1; }
warn() { printf "  ${YLW}!${NC} %-14s %s\n" "$1" "$2"; }
hint() { printf "      ${DIM}%s${NC}\n" "$1"; }

# major_ge <version-string> <required-major>
major_ge() {
  local major
  major=$(printf '%s' "$1" | grep -oE '[0-9]+' | head -1)
  [[ -n "$major" && "$major" -ge "$2" ]]
}

# minor_ge <version-string> <req-major> <req-minor>
minor_ge() {
  local maj min
  maj=$(printf '%s' "$1" | grep -oE '[0-9]+' | sed -n 1p)
  min=$(printf '%s' "$1" | grep -oE '[0-9]+' | sed -n 2p)
  [[ -n "$maj" ]] || return 1
  (( maj > $2 )) && return 0
  (( maj == $2 )) && [[ -n "$min" ]] && (( min >= $3 )) && return 0
  return 1
}

echo
echo "NexusIQ prerequisites (mode: $MODE)"
echo "───────────────────────────────────────────────────────────────"

# ---------------------------------------------------------------- infra
if ! command -v docker >/dev/null 2>&1; then
  bad "docker" "not installed"
  hint "Install Docker Desktop: https://docs.docker.com/desktop/"
else
  v=$(docker --version 2>/dev/null)
  if major_ge "$v" 24; then ok "docker" "$v"; else bad "docker" "$v (need 24+)"; fi
  if ! docker info >/dev/null 2>&1; then
    bad "docker daemon" "not running"
    hint "Start Docker Desktop, then re-run."
  fi
fi

if docker compose version >/dev/null 2>&1; then
  v=$(docker compose version --short 2>/dev/null)
  if major_ge "$v" 2; then ok "compose" "v$v"; else bad "compose" "v$v (need v2)"; fi
else
  bad "compose" "docker compose v2 not available"
  hint "Docker Desktop includes it. 'docker-compose' (v1) is not supported."
fi

# Free disk space (images + model weights + volumes ≈ 15 GB)
avail=$(df -g . 2>/dev/null | awk 'NR==2 {print $4}')
if [[ -n "${avail:-}" ]]; then
  if [[ "$avail" -ge 15 ]]; then ok "disk" "${avail}G available"
  else warn "disk" "${avail}G available (15G recommended)"; fi
fi

if [[ "$MODE" == "infra" ]]; then
  echo "───────────────────────────────────────────────────────────────"
  if [[ $FAILED -eq 0 ]]; then
    printf "${GRN}Infrastructure prerequisites satisfied.${NC}\n\n"
  else
    printf "${RED}Missing infrastructure prerequisites — see above.${NC}\n\n"
  fi
  exit $FAILED
fi

# ---------------------------------------------------------------- build
echo

if ! command -v java >/dev/null 2>&1; then
  bad "java" "not installed (need 21 LTS)"
  NOTE=1
else
  v=$(java -version 2>&1 | head -1 | grep -oE '"[^"]+"' | tr -d '"')
  # "1.8.0_392" is Java 8; anything else leads with the feature version.
  if [[ "$v" == 1.8* ]]; then
    bad "java" "$v — this is Java 8, need 21"
    NOTE=1
  elif major_ge "$v" 21; then
    ok "java" "$v"
  else
    bad "java" "$v (need 21+)"
    NOTE=1
  fi
fi

if ! command -v mvn >/dev/null 2>&1; then
  bad "maven" "not installed (need 3.9+)"
  NOTE=1
else
  v=$(mvn -version 2>/dev/null | head -1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
  if minor_ge "$v" 3 9; then ok "maven" "$v"; else bad "maven" "$v (need 3.9+)"; NOTE=1; fi
  mjraw=$(mvn -version 2>/dev/null | grep -i 'Java version' | grep -oE '[0-9]+(\.[0-9]+)*' | head -1)
  # Old-style "1.8.0_392" reports feature version 8, not 1; anything else leads
  # with the feature version already (e.g. "21.0.12" -> 21).
  if [[ "$mjraw" == 1.8* ]]; then mj=8; else mj=$(printf '%s' "$mjraw" | grep -oE '^[0-9]+'); fi
  if [[ -n "${mj:-}" && "$mj" -lt 21 ]]; then
    bad "maven/java" "Maven is running on Java $mj, not 21"
    hint "export JAVA_HOME=\$(/usr/libexec/java_home -v 21)"
    NOTE=1
  fi
fi

if ! command -v python3 >/dev/null 2>&1; then
  bad "python" "not installed (need 3.11+)"
else
  v=$(python3 --version 2>&1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+')
  if minor_ge "$v" 3 11; then
    ok "python" "$v"
  else
    bad "python" "$v (need 3.11+)"
    if command -v uv >/dev/null 2>&1; then
      hint "uv is installed — the ai-service venv can pin a newer interpreter:"
      hint "  cd ai-service && uv venv --python 3.13"
    fi
  fi
fi

command -v uv >/dev/null 2>&1 && ok "uv" "$(uv --version 2>&1)" || warn "uv" "not installed (recommended for ai-service)"

if ! command -v node >/dev/null 2>&1; then
  bad "node" "not installed (need 20+)"
else
  v=$(node --version 2>&1)
  if major_ge "${v#v}" 20; then ok "node" "$v"; else bad "node" "$v (need 20+)"; fi
fi

echo "───────────────────────────────────────────────────────────────"

if [[ $FAILED -eq 0 ]]; then
  printf "${GRN}All prerequisites satisfied.${NC}\n\n"
  exit 0
fi

printf "${RED}Missing prerequisites.${NC}\n"
if [[ -n "$NOTE" && "$(uname -s)" == "Darwin" ]]; then
  cat <<'EOF'

  macOS / Homebrew:

    brew install openjdk@21 maven
    sudo ln -sfn /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk \
                 /Library/Java/JavaVirtualMachines/openjdk-21.jdk

    # add to ~/.zshrc so it persists:
    export JAVA_HOME=$(/usr/libexec/java_home -v 21)
    export PATH="$JAVA_HOME/bin:$PATH"

  Full setup notes: docs/OPERATIONS/LOCAL_DEV.md
EOF
fi
echo
exit 1
