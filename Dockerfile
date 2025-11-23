# Build stage
FROM golang:1.23-alpine AS builder

# Install build dependencies including protoc
RUN apk add --no-cache git protoc protobuf-dev

# Install Go protobuf plugins
RUN go install google.golang.org/protobuf/cmd/protoc-gen-go@latest && \
    go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest

# Set working directory
WORKDIR /app

# Copy the entire chat-server directory
COPY ./conference-server ./

# Generate protobuf files (needed because .pb.go files are gitignored)
RUN protoc --go_out=. --go_opt=paths=source_relative \
    --go-grpc_out=. --go-grpc_opt=paths=source_relative \
    conference/conference.proto

# Download dependencies and verify
# go mod tidy regenerates go.sum if missing (needed for Coolify deployment)
RUN go mod tidy
RUN go mod download
RUN go mod verify

# Build the application
RUN CGO_ENABLED=0 GOOS=linux go build -a -installsuffix cgo -o server .

# Runtime stage
FROM alpine:latest

# Install ca-certificates for HTTPS connections
RUN apk --no-cache add ca-certificates

WORKDIR /app

# Copy the binary from builder
COPY --from=builder /app/server ./server

# Expose gRPC port
EXPOSE 50051

# Run the server
CMD ["./server"]
