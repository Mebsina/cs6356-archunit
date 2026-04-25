# Makefile - arch-go enforcement for github.com/hashicorp/consul
#
# Requires a POSIX shell (Git Bash, MSYS2, WSL, or Unix). Stock Windows
# PowerShell without sh/make will not run these targets as written.
#
# Usage:
#   make arch-check                          # run from the consul repo root
#   make arch-check PROJECT_ROOT=../../..    # run from this rules directory
#
# Variables:
#   PROJECT_ROOT  Path to the root of the hashicorp/consul repository.
#                 Defaults to the current working directory.
#                 Override when invoking make from a different directory:
#                   make arch-check PROJECT_ROOT=/path/to/consul

PROJECT_ROOT ?= .

# arch-go must be re-resolved inside each recipe's shell so that targets that
# install arch-go in the same `make` invocation see the freshly installed
# binary on $PATH. Capturing $(shell command -v arch-go) at parse time would
# lock in an empty value when the binary is missing on first run; no
# make-level variable is used for that reason. Each recipe runs `command -v`
# itself.
ARCH_GO_INSTALL := go install github.com/fdaines/arch-go@latest

.PHONY: arch-verify-module
arch-verify-module:
	@echo "==> Verifying Go module path at $(PROJECT_ROOT)..."
	@cd "$(PROJECT_ROOT)" && [ "$$(go list -m -f '{{.Path}}' . 2>/dev/null)" = "github.com/hashicorp/consul" ] || (echo "ERROR: expected module github.com/hashicorp/consul (set PROJECT_ROOT to the consul repo root)" && exit 1)
	@echo "OK: module path is github.com/hashicorp/consul"

# arch-version-check asserts the installed arch-go binary is the v1 fork
# (github.com/fdaines/arch-go) that this YAML targets. The arch-go/arch-go
# v2 maintained line accepts most fields but changes the
# `interfaceImplementationNamingRule` shape (string vs nested
# internal/external/standard map); without the explicit `version: 1` in
# arch-go.yml plus this binary check, behaviour silently diverges across the
# two versions.
.PHONY: arch-version-check
arch-version-check:
	@echo "==> Verifying installed arch-go is the v1 fork..."
	@bin="$$(command -v arch-go 2>/dev/null)"; \
	if [ -z "$$bin" ]; then \
		echo "ERROR: arch-go not found on PATH (run 'make arch-install' first)"; \
		exit 1; \
	fi; \
	"$$bin" --version 2>&1 | grep -E '(arch-go|fdaines).* v?1\.' >/dev/null \
	  || (echo "ERROR: this YAML targets fdaines/arch-go v1; got a different version (see $$bin --version)"; exit 1)
	@echo "OK: arch-go is the v1 fork (compatible with this YAML schema)"

.PHONY: arch-check
arch-check:
	@echo "==> Checking for arch-go binary..."
	@bin="$$(command -v arch-go 2>/dev/null)"; \
	if [ -z "$$bin" ]; then \
		echo "==> arch-go not found. Installing via: $(ARCH_GO_INSTALL)"; \
		$(ARCH_GO_INSTALL); \
		bin="$$(command -v arch-go 2>/dev/null)"; \
	fi; \
	if [ -z "$$bin" ]; then \
		echo "ERROR: arch-go not found on PATH after install (check \$$GOBIN / \$$GOPATH/bin)"; \
		exit 1; \
	fi; \
	echo "==> arch-go resolved at $$bin"; \
	echo "==> Running arch-go check in $(PROJECT_ROOT)"; \
	cd "$(PROJECT_ROOT)" && "$$bin" check
# Note: upstream arch-go does not document a --exclude-files flag; *_test.go
# files are evaluated like production code when matched by a rule. The YAML
# header documents test-only paths and static-analysis limits. Coverage metrics
# from the tool, if any, are not a constraint matrix for the YAML.

.PHONY: arch-check-verbose
arch-check-verbose:
	@echo "==> Checking for arch-go binary..."
	@bin="$$(command -v arch-go 2>/dev/null)"; \
	if [ -z "$$bin" ]; then \
		echo "==> arch-go not found. Installing via: $(ARCH_GO_INSTALL)"; \
		$(ARCH_GO_INSTALL); \
		bin="$$(command -v arch-go 2>/dev/null)"; \
	fi; \
	if [ -z "$$bin" ]; then \
		echo "ERROR: arch-go not found on PATH after install (check \$$GOBIN / \$$GOPATH/bin)"; \
		exit 1; \
	fi; \
	echo "==> arch-go resolved at $$bin"; \
	echo "==> Running arch-go check (verbose) in $(PROJECT_ROOT)"; \
	cd "$(PROJECT_ROOT)" && "$$bin" check -v

.PHONY: arch-install
arch-install:
	@echo "==> Installing arch-go..."
	$(ARCH_GO_INSTALL)
	@echo "==> arch-go installed."

.PHONY: help
help:
	@echo ""
	@echo "arch-go Makefile targets for github.com/hashicorp/consul"
	@echo ""
	@echo "  arch-check          Install arch-go (if missing) and run 'arch-go check'"
	@echo "  arch-check-verbose  Same as arch-check but with verbose output (-v)"
	@echo "  arch-verify-module  Assert go list -m reports github.com/hashicorp/consul"
	@echo "  arch-version-check  Assert installed arch-go is the v1 fork (fdaines/arch-go)"
	@echo "  arch-install        Force (re-)install arch-go from source"
	@echo "  help                Show this help message"
	@echo ""
	@echo "Shell: targets use POSIX [ and command -v; use Git Bash / WSL on Windows."
	@echo ""
	@echo "Variables:"
	@echo "  PROJECT_ROOT  Root of the consul repository (default: current directory)"
	@echo "                Example: make arch-check PROJECT_ROOT=../../.."
	@echo ""
