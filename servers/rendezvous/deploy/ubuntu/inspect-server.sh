#!/usr/bin/env bash
# Read-only inventory. Do not print process arguments, environments or account files.
set -u

task_unit=${1:-}
if (( $# > 1 )) || { [[ -n "$task_unit" ]] && [[ ! "$task_unit" =~ ^[a-zA-Z0-9_][a-zA-Z0-9_.@-]*\.service$ ]]; }; then
    printf 'Usage: bash inspect-server.sh [name.service]\n' >&2
    exit 2
fi

printf 'Operating system\n'
if [[ -r /etc/os-release ]]; then
    awk '/^(PRETTY_NAME|VERSION_ID)=/' /etc/os-release
fi

printf '\nJava\n'
if command -v java >/dev/null 2>&1; then
    java -version 2>&1 | awk 'NR <= 3'
else
    printf 'Not available in PATH\n'
fi

printf '\nProxy tools\n'
for task_tool in nginx cloudflared; do
    if command -v "$task_tool" >/dev/null 2>&1; then
        printf '%s: installed\n' "$task_tool"
    else
        printf '%s: not available in PATH\n' "$task_tool"
    fi
done

printf '\nServer service\n'
if command -v systemctl >/dev/null 2>&1; then
    if [[ -n "$task_unit" ]]; then
        # Deliberately exclude ExecStart and Environment, which can contain secrets.
        systemctl show "$task_unit" --no-pager \
            --property=Id,LoadState,ActiveState,SubState,User,WorkingDirectory,FragmentPath,MainPID
    else
        printf 'Candidate unit names (custom names may be missing):\n'
        systemctl list-unit-files --type=service --no-legend --no-pager 2>/dev/null |
            awk 'tolower($1) ~ /peercraft|rendezvous/ { print $1 }'
        printf 'Repeat with the actual unit name to inspect its non-secret metadata.\n'
    fi
else
    printf 'systemctl unavailable; server may be started manually\n'
fi

printf '\nListening UDP sockets\n'
if command -v ss >/dev/null 2>&1; then
    ss -H -lun
else
    printf 'ss unavailable\n'
fi

printf '\nNo services, files, firewall rules or relay settings were changed.\n'
printf 'JAR path and effective dataDir still require inspection before deployment.\n'
