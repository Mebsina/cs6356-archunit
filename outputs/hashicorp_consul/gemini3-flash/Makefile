# Makefile - arch-go enforcement for HashiCorp Consul
#
# This Makefile ensures that arch-go is installed and runs the architectural
# checks against the project.
#
# Usage:
#   make arch-check
#   make arch-check PROJECT_ROOT=../../..
#

# Path to the project root where arch-go check should be executed.
# Defaults to the current directory.
PROJECT_ROOT ?= .

# Target to install arch-go if not already present and run the check.
.PHONY: arch-check
arch-check:
	@echo "==> Verifying arch-go installation..."
	@go install github.com/fdaines/arch-go@latest
	@echo "==> Running arch-go check in $(PROJECT_ROOT)..."
	@cd $(PROJECT_ROOT) && arch-go check

# Target to display help information.
.PHONY: help
help:
	@echo "Consul Architecture Check Makefile"
	@echo ""
	@echo "Targets:"
	@echo "  arch-check    Installs arch-go and runs 'arch-go check' from PROJECT_ROOT"
	@echo "  help          Show this help message"
	@echo ""
	@echo "Variables:"
	@echo "  PROJECT_ROOT  Root directory of the project (default: .)"
	@echo "                Example: make arch-check PROJECT_ROOT=/path/to/consul"
