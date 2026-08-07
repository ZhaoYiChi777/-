# Multi-stage build for Rust kg-compute sidecar
FROM rust:1.88-alpine AS builder

# Install build dependencies
RUN apk add --no-cache musl-dev

# Set working directory
WORKDIR /app

# Copy dependency configs
COPY Cargo.toml Cargo.lock ./

# Create dummy source and build dependencies to cache them
RUN mkdir src && echo "fn main() {}" > src/main.rs && cargo build --release && rm -rf src

# Copy actual source files
COPY src ./src

# Build the release binary
RUN touch src/main.rs && cargo build --release

# Production stage: copy binary to a minimal image
FROM alpine:latest
WORKDIR /app
COPY --from=builder /app/target/release/kg-compute /app/kg-compute
EXPOSE 8101
ENTRYPOINT ["/app/kg-compute"]
