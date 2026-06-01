#!/bin/bash
# Sync the pixel-sensor-lab submodules in one command.
#
#   ./sync-submodules.sh            # init + checkout each submodule at the recorded pointer
#   ./sync-submodules.sh --remote   # fast-forward each submodule to latest origin/<branch>
#                                     (branch is per-submodule in .gitmodules), then report
#                                     which pointers moved so you can commit them deliberately
#
# Rationale: the recurring submodule footgun is forgetting to bump/commit pointers, leaving
# the umbrella pointing at stale commits. --remote + the change report makes that explicit.
set -euo pipefail
cd "$(dirname "$0")"

if [[ "${1:-}" == "--remote" ]]; then
	echo "Fast-forwarding submodules to latest tracked branch (per .gitmodules)..."
	git submodule update --init --remote --recursive
	echo
	moved=$(git status --porcelain | grep '^ M sensors/' || true)
	if [[ -n "$moved" ]]; then
		echo "Pointers moved (commit these to record the bump):"
		echo "$moved"
		echo
		echo "  git add sensors/<name> && git commit -m 'chore: bump <name> submodule'"
	else
		echo "All submodule pointers already current."
	fi
else
	echo "Syncing submodules to recorded pointers..."
	git submodule sync --recursive
	git submodule update --init --recursive
	echo "Done."
fi

echo
echo "Status:"
git submodule status
