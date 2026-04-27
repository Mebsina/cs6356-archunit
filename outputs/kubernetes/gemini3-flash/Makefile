# Makefile for arch-go architectural check
# Portable across environments using PROJECT_ROOT variable.

# Default project root is the current directory if not specified.
PROJECT_ROOT ?= .

# arch-go installation version
ARCH_GO_VERSION := latest

.PHONY: arch-check
arch-check:
	@echo "Checking for arch-go installation..."
	@if ! command -v arch-go > /dev/null; then \
		echo "arch-go not found. Installing github.com/fdaines/arch-go@$(ARCH_GO_VERSION)..."; \
		go install github.com/fdaines/arch-go@$(ARCH_GO_VERSION); \
	else \
		echo "arch-go is already installed."; \
	fi
	@echo "Executing arch-go check on project root: $(PROJECT_ROOT)"
	@# Copy the configuration file to the project root to ensure it's picked up by arch-go
	@# We use an absolute path for the source arch-go.yml to be safe
	@CONFIG_SRC=$(shell pwd)/arch-go.yml; \
	cp $$CONFIG_SRC $(PROJECT_ROOT)/arch-go.yml
	@cd $(PROJECT_ROOT) && arch-go check
	@# Clean up the copied configuration file from the project root
	@rm $(PROJECT_ROOT)/arch-go.yml
	@echo "Arch-check completed."
