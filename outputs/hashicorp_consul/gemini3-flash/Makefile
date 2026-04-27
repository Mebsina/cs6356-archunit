# Makefile - arch-go enforcement for HashiCorp Consul
#
# This Makefile ensures that arch-go is installed and runs the architectural
# checks against the project. arch-go has NO `check` subcommand and NO
# `--config` flag — it always reads `arch-go.yml` from the current working
# directory. To accommodate this, this Makefile copies the local `arch-go.yml`
# into PROJECT_ROOT (which must contain `go.mod`) before invoking arch-go and
# removes the copy afterward via a shell `trap`, so abnormal exits (Ctrl-C,
# SIGTERM, OOM kill) also clean up the staged file.
#
# Hardening (Adversarial Review #3):
#   F1 — verify the installed arch-go is v2; a stale v1.x binary on PATH
#        rejects v2 schema features and aborts the YAML unmarshal.
#   F8 — refuse to clobber a developer's pre-existing `arch-go.yml` in
#        PROJECT_ROOT, and use `trap '... EXIT'` so the staged copy is
#        removed even on Ctrl-C / SIGTERM.
#   F9 — assert that arch-go printed a `Compliance:` line in its report,
#        so a future regression that causes the YAML to fail to parse
#        surfaces as a hard CI failure rather than silent zero-rule
#        evaluation.
#
# Usage:
#   make arch-check
#   make arch-check PROJECT_ROOT=/path/to/consul
#

# Path to the consul module root (must contain go.mod).
# Defaults to the current directory.
PROJECT_ROOT ?= .

# Absolute path to the arch-go.yml that lives next to this Makefile.
ARCH_CONFIG  ?= $(abspath arch-go.yml)

# Where to capture the arch-go report so we can grep it for the
# `Compliance:` sentinel. Override if /tmp is unavailable.
ARCH_REPORT  ?= /tmp/arch-go-report.txt

# Target to install arch-go if not already present and run the check.
# We install from the actively maintained `arch-go/arch-go/v2` org. The
# `command -v` short-circuit alone is not enough — a stale v1 binary
# previously installed by `fdaines/arch-go` would silently win, so we
# additionally probe `arch-go --version` and re-install if it isn't v2.
.PHONY: arch-check
arch-check:
	@echo "==> Verifying arch-go (v2)..."
	@if command -v arch-go >/dev/null 2>&1; then \
	  if arch-go --version 2>/dev/null | grep -qE '(^|[^0-9])v?2\.'; then \
	    echo "    arch-go v2 already installed."; \
	  else \
	    echo "    Installed arch-go is not v2; reinstalling from arch-go.org/v2..."; \
	    go install github.com/arch-go/arch-go/v2@latest; \
	  fi; \
	else \
	  echo "    arch-go not on PATH; installing from arch-go.org/v2..."; \
	  go install github.com/arch-go/arch-go/v2@latest; \
	fi
	@if [ -e "$(PROJECT_ROOT)/arch-go.yml" ]; then \
	  echo "ERROR: $(PROJECT_ROOT)/arch-go.yml already exists. Refusing to overwrite." >&2; \
	  echo "       Move it aside first, or run from a different PROJECT_ROOT." >&2; \
	  exit 1; \
	fi
	@echo "==> Staging $(ARCH_CONFIG) into $(PROJECT_ROOT)/arch-go.yml..."
	@echo "==> Running arch-go in $(PROJECT_ROOT)..."
	@trap 'rm -f "$(PROJECT_ROOT)/arch-go.yml"' EXIT INT TERM; \
	  cp -f "$(ARCH_CONFIG)" "$(PROJECT_ROOT)/arch-go.yml" && \
	  cd "$(PROJECT_ROOT)" && arch-go --verbose 2>&1 | tee "$(ARCH_REPORT)"; \
	  status=$${PIPESTATUS[0]}; \
	  if ! grep -qE '^Compliance[: ]' "$(ARCH_REPORT)"; then \
	    echo "" >&2; \
	    echo "FAIL: arch-go did not print a 'Compliance:' line." >&2; \
	    echo "      The YAML may have failed to parse — every rule is unenforced." >&2; \
	    exit 1; \
	  fi; \
	  exit $$status

# Target to display help information.
.PHONY: help
help:
	@echo "Consul Architecture Check Makefile"
	@echo ""
	@echo "Targets:"
	@echo "  arch-check    Installs arch-go (v2), refuses to clobber an"
	@echo "                existing $(PROJECT_ROOT)/arch-go.yml, stages the"
	@echo "                local arch-go.yml under a trap, runs arch-go, and"
	@echo "                asserts that a 'Compliance:' line was printed."
	@echo "  help          Show this help message"
	@echo ""
	@echo "Variables:"
	@echo "  PROJECT_ROOT  Consul module root (must contain go.mod) (default: .)"
	@echo "  ARCH_CONFIG   Absolute path to arch-go.yml (default: ./arch-go.yml)"
	@echo "  ARCH_REPORT   Path for the captured arch-go report (default: /tmp/arch-go-report.txt)"
	@echo ""
	@echo "Example:"
	@echo "  make arch-check PROJECT_ROOT=/path/to/consul"
