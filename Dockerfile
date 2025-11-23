# Build stage
FROM golang:1.23-alpine AS builder

# Install build dependencies
RUN apk add --no-cache git

# Set working directory
WORKDIR /app

# Copy the entire chat-server directory
COPY ./chat-server ./

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
