# =============================================================================
# Makefile - arch-go architectural constraint checker for Kubernetes
#
# Usage:
#   make arch-check                          # run from repo root
#   make arch-check PROJECT_ROOT=../../..    # portable, specify repo root
#   make install-arch-go                     # install arch-go only
#   make help                                # show available targets
#
# Requirements:
#   - Go toolchain (go install, go env, go list)
#   - arch-go (installed automatically by arch-check if missing)
#   - POSIX shell (sh, bash) for the recipe shell pipelines below
#
# Review #2 [F1] hardening:
#   The arch-check target now refuses to run unless the module declared by
#   $(PROJECT_ROOT)/go.mod is exactly $(ARCH_GO_EXPECTED_MODULE), and it
#   fails the build if arch-go's COVERAGE RATE drops below
#   $(ARCH_GO_MIN_COVERAGE). Without these guards, a stub go.mod under
#   PROJECT_ROOT silently produced a 0%-coverage / 100%-compliance run.
# =============================================================================

# Review #3 [F1] hardening:
#   The recipe pipeline `arch-go check | tee` previously captured `$?` of
#   `tee` (always 0), so arch-go violations never propagated. We now switch
#   to bash with pipefail so PIPESTATUS[0] is preserved even when piping to
#   tee, ensuring real arch-go violations exit non-zero.
SHELL := /bin/bash
.SHELLFLAGS := -eu -o pipefail -c

# Default project root is the directory containing this Makefile.
# Override with: make arch-check PROJECT_ROOT=/path/to/kubernetes
PROJECT_ROOT ?= $(CURDIR)

# arch-go binary name and install package.
ARCH_GO_BIN := arch-go
ARCH_GO_PKG := github.com/fdaines/arch-go@latest

# Module identity that the rules in arch-go.yml are written against.
# The Makefile refuses to run against any other module.
ARCH_GO_EXPECTED_MODULE := k8s.io/kubernetes

# Minimum acceptable coverage rate (percentage) reported by arch-go.
# A run that scans zero matching packages would return 0%; we floor at 30%
# to assert the rules are actually being exercised.
ARCH_GO_MIN_COVERAGE := 30

# Detect OS for binary lookup compatibility.
GOPATH := $(shell go env GOPATH)
ARCH_GO_PATH := $(GOPATH)/bin/$(ARCH_GO_BIN)

.PHONY: help install-arch-go arch-check

## help: Display available make targets and their descriptions
help:
	@echo ""
	@echo "Kubernetes arch-go Architectural Constraint Checker"
	@echo "===================================================="
	@echo ""
	@echo "Targets:"
	@echo "  arch-check      Install arch-go if needed, verify the module is"
	@echo "                  $(ARCH_GO_EXPECTED_MODULE), and run 'arch-go check'"
	@echo "                  with a coverage floor of $(ARCH_GO_MIN_COVERAGE)%."
	@echo "  install-arch-go Install arch-go via 'go install' (idempotent)."
	@echo "  help            Show this help message."
	@echo ""
	@echo "Variables:"
	@echo "  PROJECT_ROOT    Path to the Kubernetes repository root."
	@echo "                  Default: $(PROJECT_ROOT)"
	@echo "  ARCH_GO_EXPECTED_MODULE"
	@echo "                  Expected module path declared by go.mod."
	@echo "                  Default: $(ARCH_GO_EXPECTED_MODULE)"
	@echo "  ARCH_GO_MIN_COVERAGE"
	@echo "                  Minimum acceptable arch-go COVERAGE RATE."
	@echo "                  Default: $(ARCH_GO_MIN_COVERAGE)"
	@echo ""
	@echo "Examples:"
	@echo "  make arch-check"
	@echo "  make arch-check PROJECT_ROOT=/home/user/go/src/k8s.io/kubernetes"
	@echo ""

## install-arch-go: Install arch-go binary via go install (idempotent)
install-arch-go:
	@if ! command -v $(ARCH_GO_BIN) > /dev/null 2>&1; then \
		echo "[arch-go] arch-go not found in PATH. Installing $(ARCH_GO_PKG)..."; \
		go install $(ARCH_GO_PKG); \
		echo "[arch-go] Installation complete: $$(command -v $(ARCH_GO_BIN))"; \
	else \
		echo "[arch-go] arch-go already installed: $$(command -v $(ARCH_GO_BIN))"; \
	fi

## arch-check: Install arch-go if missing, verify module identity, copy
##             config, run architectural checks, and assert non-trivial
##             coverage.
arch-check: install-arch-go
	@echo ""
	@echo "[arch-go] Running architectural constraint checks..."
	@echo "[arch-go] Project root      : $(PROJECT_ROOT)"
	@echo "[arch-go] Config file       : $(CURDIR)/arch-go.yml"
	@echo "[arch-go] Expected module   : $(ARCH_GO_EXPECTED_MODULE)"
	@echo "[arch-go] Min coverage rate : $(ARCH_GO_MIN_COVERAGE)%"
	@echo ""
	@if [ ! -f "$(PROJECT_ROOT)/go.mod" ]; then \
		echo "[arch-go] ERROR: No go.mod found at PROJECT_ROOT=$(PROJECT_ROOT)."; \
		echo "[arch-go] Set PROJECT_ROOT to the Kubernetes repository root."; \
		exit 1; \
	fi
	@cd "$(PROJECT_ROOT)" && \
		MOD=$$(go list -m 2>/dev/null); \
		if [ "$$MOD" != "$(ARCH_GO_EXPECTED_MODULE)" ]; then \
			echo "[arch-go] FAIL: expected module $(ARCH_GO_EXPECTED_MODULE), got '$$MOD'."; \
			echo "[arch-go] Refusing to run against the wrong module."; \
			echo "[arch-go] Point PROJECT_ROOT at a checkout whose go.mod declares"; \
			echo "[arch-go] 'module $(ARCH_GO_EXPECTED_MODULE)'."; \
			exit 1; \
		fi
	@cp "$(CURDIR)/arch-go.yml" "$(PROJECT_ROOT)/arch-go.yml"
	@echo "[arch-go] Copied arch-go.yml to $(PROJECT_ROOT)/arch-go.yml"
	@cd "$(PROJECT_ROOT)" && \
		OUT_FILE=$$(mktemp -t arch-go.XXXXXX.out); \
		set -o pipefail; \
		$(ARCH_GO_BIN) check | tee "$$OUT_FILE"; \
		STATUS=$${PIPESTATUS[0]}; \
		set +o pipefail; \
		echo ""; \
		COV=$$(grep -E 'COVERAGE RATE' "$$OUT_FILE" | grep -oE '[0-9]+' | head -n1); \
		if [ -z "$$COV" ]; then \
			echo "[arch-go] FAIL: no COVERAGE RATE line found in arch-go output."; \
			echo "[arch-go] arch-go may have errored before producing a report."; \
			rm -f "$$OUT_FILE"; \
			exit 1; \
		fi; \
		if [ "$$COV" -lt "$(ARCH_GO_MIN_COVERAGE)" ]; then \
			echo "[arch-go] FAIL: COVERAGE RATE $$COV%% is below the floor of $(ARCH_GO_MIN_COVERAGE)%%."; \
			echo "[arch-go] Either widen the rules in arch-go.yml or verify the module"; \
			echo "[arch-go] under PROJECT_ROOT actually contains the targeted packages."; \
			rm -f "$$OUT_FILE"; \
			exit 1; \
		fi; \
		echo "[arch-go] COVERAGE RATE $$COV%% (floor $(ARCH_GO_MIN_COVERAGE)%%): OK."; \
		rm -f "$$OUT_FILE"; \
		if [ $$STATUS -eq 0 ]; then \
			echo "[arch-go] All architectural constraints passed."; \
		else \
			echo "[arch-go] Architectural violations detected (exit code: $$STATUS)."; \
			echo "[arch-go] Review the violations above and update source or arch-go.yml."; \
		fi; \
		exit $$STATUS
