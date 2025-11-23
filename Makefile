SHELL := /bin/bash

.PHONY: help proto clean install-deps check-tools \
	server server-proto server-build server-run \
	go-client go-client-proto go-client-build go-client-run \
	python-client python-client-proto python-client-run \
	rust-client rust-client-build rust-client-run \
	java-client java-client-proto java-client-build java-client-run \
	all build

# Directorios
SERVER_DIR := chat-server
GO_CLIENT_DIR := go-client
PYTHON_CLIENT_DIR := python-client
RUST_CLIENT_DIR := rust-client
JAVA_CLIENT_DIR := java-client

# Go tools paths
GOPATH := $(shell go env GOPATH)
GOBIN := $(GOPATH)/bin
export PATH := $(GOBIN):$(PATH)

# Default target
help:
	@echo -e "\033[0;34m═══════════════════════════════════════════════════════════════\033[0m"
	@echo -e "\033[0;32m           gRPC Chat Project - Makefile Commands\033[0m"
	@echo -e "\033[0;34m═══════════════════════════════════════════════════════════════\033[0m"
	@echo ""
	@echo -e "\033[0;33mGeneral Commands:\033[0m"
	@echo -e "  \033[0;32mmake help\033[0m          - Show this help message"
	@echo -e "  \033[0;32mmake check-tools\033[0m   - Check if required tools are installed"
	@echo -e "  \033[0;32mmake install-deps\033[0m  - Install all dependencies"
	@echo -e "  \033[0;32mmake proto\033[0m         - Generate protobuf code for all projects"
	@echo -e "  \033[0;32mmake build\033[0m         - Build all projects"
	@echo -e "  \033[0;32mmake clean\033[0m         - Clean generated files"
	@echo ""
	@echo -e "\033[0;33mServer Commands (Go):\033[0m"
	@echo -e "  \033[0;32mmake server-proto\033[0m  - Generate server protobuf code"
	@echo -e "  \033[0;32mmake server-build\033[0m  - Build the server"
	@echo -e "  \033[0;32mmake server-run\033[0m    - Run the server"
	@echo -e "  \033[0;32mmake server\033[0m        - Generate proto, build and run server"
	@echo ""
	@echo -e "\033[0;33mGo Client Commands:\033[0m"
	@echo -e "  \033[0;32mmake go-client-proto\033[0m - Generate Go client protobuf code"
	@echo -e "  \033[0;32mmake go-client-build\033[0m - Build the Go client"
	@echo -e "  \033[0;32mmake go-client-run\033[0m   - Run the Go client"
	@echo -e "  \033[0;32mmake go-client\033[0m       - Generate proto, build and run Go client"
	@echo ""
	@echo -e "\033[0;33mPython Client Commands:\033[0m"
	@echo -e "  \033[0;32mmake python-client-proto\033[0m - Generate Python client protobuf code"
	@echo -e "  \033[0;32mmake python-client-run\033[0m   - Run the Python client"
	@echo -e "  \033[0;32mmake python-client\033[0m       - Generate proto and run Python client"
	@echo ""
	@echo -e "\033[0;33mRust Client Commands:\033[0m"
	@echo -e "  \033[0;32mmake rust-client-build\033[0m - Build the Rust client"
	@echo -e "  \033[0;32mmake rust-client-run\033[0m   - Run the Rust client"
	@echo -e "  \033[0;32mmake rust-client\033[0m       - Build and run Rust client"
	@echo ""
	@echo -e "\033[0;33mJava Client Commands:\033[0m"
	@echo -e "  \033[0;32mmake java-client-proto\033[0m - Generate Java client protobuf code"
	@echo -e "  \033[0;32mmake java-client-build\033[0m - Build the Java client"
	@echo -e "  \033[0;32mmake java-client-run\033[0m   - Run the Java client"
	@echo -e "  \033[0;32mmake java-client\033[0m       - Generate proto, build and run Java client"
	@echo ""
	@echo -e "\033[0;34m═══════════════════════════════════════════════════════════════\033[0m"

# Check if required tools are installed
check-tools:
	@echo -e "\033[0;34mChecking required tools...\033[0m"
	@echo -n -e "\033[0;33mChecking Go...\033[0m "
	@which go > /dev/null 2>&1 && echo -e "\033[0;32m✓\033[0m" || echo -e "\033[0;31m✗ Not found\033[0m"
	@echo -n -e "\033[0;33mChecking protoc...\033[0m "
	@which protoc > /dev/null 2>&1 && echo -e "\033[0;32m✓\033[0m" || echo -e "\033[0;31m✗ Not found\033[0m"
	@echo -n -e "\033[0;33mChecking protoc-gen-go...\033[0m "
	@which protoc-gen-go > /dev/null 2>&1 && echo -e "\033[0;32m✓\033[0m" || echo -e "\033[0;31m✗ Not found (run: go install google.golang.org/protobuf/cmd/protoc-gen-go@latest)\033[0m"
	@echo -n -e "\033[0;33mChecking protoc-gen-go-grpc...\033[0m "
	@which protoc-gen-go-grpc > /dev/null 2>&1 && echo -e "\033[0;32m✓\033[0m" || echo -e "\033[0;31m✗ Not found (run: go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest)\033[0m"
	@echo -n -e "\033[0;33mChecking Poetry...\033[0m "
	@which poetry > /dev/null 2>&1 && echo -e "\033[0;32m✓\033[0m" || echo -e "\033[0;31m✗ Not found (run: curl -sSL https://install.python-poetry.org | python3 -)\033[0m"
	@echo -n -e "\033[0;33mChecking Rust/Cargo...\033[0m "
	@which cargo > /dev/null 2>&1 && echo -e "\033[0;32m✓\033[0m" || echo -e "\033[0;31m✗ Not found\033[0m"
	@echo -n -e "\033[0;33mChecking Maven...\033[0m "
	@which mvn > /dev/null 2>&1 && echo -e "\033[0;32m✓\033[0m" || echo -e "\033[0;31m✗ Not found\033[0m"

# Install all dependencies
install-deps:
	@echo -e "\033[0;34mInstalling dependencies...\033[0m"
	@echo -e "\033[0;33mInstalling Go dependencies...\033[0m"
	@cd $(SERVER_DIR) && go mod tidy
	@cd $(GO_CLIENT_DIR) && go mod tidy
	@echo -e "\033[0;33mInstalling Python dependencies with Poetry...\033[0m"
	@cd $(PYTHON_CLIENT_DIR) && poetry install
	@echo -e "\033[0;33mChecking Rust...\033[0m"
	@cargo --version || echo -e "\033[0;31mPlease install Rust from https://rustup.rs/\033[0m"
	@echo -e "\033[0;33mChecking Java/Maven...\033[0m"
	@mvn --version || echo -e "\033[0;31mPlease install Maven from https://maven.apache.org/\033[0m"
	@echo -e "\033[0;32mDependencies installation completed!\033[0m"

# Generate all proto files
proto: server-proto go-client-proto python-client-proto java-client-proto
	@echo -e "\033[0;32mAll proto files generated!\033[0m"

# Build all projects
build: server-build go-client-build rust-client-build java-client-build
	@echo -e "\033[0;32mAll projects built!\033[0m"

# Clean all generated files
clean:
	@echo -e "\033[0;33mCleaning generated files...\033[0m"
	@cd $(SERVER_DIR) && rm -f chat/*.pb.go
	@cd $(GO_CLIENT_DIR) && rm -f chat/*.pb.go
	@cd $(PYTHON_CLIENT_DIR) && rm -f proto/*_pb2.py proto/*_pb2_grpc.py proto/*_pb2.pyi
	@cd $(RUST_CLIENT_DIR) && cargo clean
	@cd $(JAVA_CLIENT_DIR) && mvn clean
	@echo -e "\033[0;32mClean completed!\033[0m"

# ═══════════════════════════════════════════════════════════════
# SERVER (Go)
# ═══════════════════════════════════════════════════════════════

server-proto:
	@echo -e "\033[0;34mGenerating server protobuf code...\033[0m"
	@cd $(SERVER_DIR) && protoc --go_out=. --go_opt=paths=source_relative \
		--go-grpc_out=. --go-grpc_opt=paths=source_relative \
		chat/chat.proto
	@echo -e "\033[0;32mServer proto files generated!\033[0m"

server-build: server-proto
	@echo -e "\033[0;34mBuilding server...\033[0m"
	@cd $(SERVER_DIR) && go build -o server .
	@echo -e "\033[0;32mServer built successfully!\033[0m"

server-run: server-build
	@echo -e "\033[0;32mStarting server on port 50051...\033[0m"
	@cd $(SERVER_DIR) && ./server

server: server-run

# ═══════════════════════════════════════════════════════════════
# GO CLIENT
# ═══════════════════════════════════════════════════════════════

go-client-proto:
	@echo -e "\033[0;34mGenerating Go client protobuf code...\033[0m"
	@cd $(GO_CLIENT_DIR) && protoc --go_out=. --go_opt=paths=source_relative \
		--go-grpc_out=. --go-grpc_opt=paths=source_relative \
		chat/chat.proto
	@echo -e "\033[0;32mGo client proto files generated!\033[0m"

go-client-build: go-client-proto
	@echo -e "\033[0;34mBuilding Go client...\033[0m"
	@cd $(GO_CLIENT_DIR) && go build -o client .
	@echo -e "\033[0;32mGo client built successfully!\033[0m"

go-client-run: go-client-build
	@echo -e "\033[0;32mStarting Go client...\033[0m"
	@cd $(GO_CLIENT_DIR) && ./client

go-client: go-client-run

# ═══════════════════════════════════════════════════════════════
# PYTHON CLIENT
# ═══════════════════════════════════════════════════════════════

python-client-proto:
	@echo -e "\033[0;34mGenerating Python client protobuf code...\033[0m"
	@cd $(PYTHON_CLIENT_DIR) && poetry run python -m grpc_tools.protoc -I. \
		--python_out=. --grpc_python_out=. --pyi_out=. \
		proto/chat.proto
	@echo -e "\033[0;32mPython client proto files generated!\033[0m"

python-client-run: python-client-proto
	@echo -e "\033[0;32mStarting Python client...\033[0m"
	@cd $(PYTHON_CLIENT_DIR) && poetry run python chat_client.py

python-client: python-client-run

# ═══════════════════════════════════════════════════════════════
# RUST CLIENT
# ═══════════════════════════════════════════════════════════════

rust-client-build:
	@echo -e "\033[0;34mBuilding Rust client...\033[0m"
	@cd $(RUST_CLIENT_DIR) && cargo build --release
	@echo -e "\033[0;32mRust client built successfully!\033[0m"

rust-client-run:
	@echo -e "\033[0;32mStarting Rust client...\033[0m"
	@cd $(RUST_CLIENT_DIR) && cargo run --release

rust-client: rust-client-run

# ═══════════════════════════════════════════════════════════════
# JAVA CLIENT
# ═══════════════════════════════════════════════════════════════

java-client-proto:
	@echo -e "\033[0;34mGenerating Java client protobuf code...\033[0m"
	@cd $(JAVA_CLIENT_DIR) && mvn protobuf:compile protobuf:compile-custom
	@echo -e "\033[0;32mJava client proto files generated!\033[0m"

java-client-build: java-client-proto
	@echo -e "\033[0;34mBuilding Java client...\033[0m"
	@cd $(JAVA_CLIENT_DIR) && mvn compile
	@echo -e "\033[0;32mJava client built successfully!\033[0m"

java-client-run: java-client-build
	@echo -e "\033[0;32mStarting Java client...\033[0m"
	@cd $(JAVA_CLIENT_DIR) && mvn exec:java

java-client: java-client-run

# ═══════════════════════════════════════════════════════════════
# ALL
# ═══════════════════════════════════════════════════════════════

all: proto build
	@echo -e "\033[0;32mAll projects ready!\033[0m"
