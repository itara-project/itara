#!/usr/bin/env bash
# setup-rust-env.sh
#
# Sets up the Rust build environment for the Itara demo.
# Run this once before building for the first time.
#
# On Windows: run in WSL (Ubuntu recommended)
# On Linux/Mac: run directly

set -e

echo "Installing Rust toolchain..."
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
source "$HOME/.cargo/env"

echo "Installing build dependencies..."
sudo apt install -y build-essential
sudo apt install -y libssl-dev
sudo apt install -y pkg-config

echo "Adding Linux target for cross-compilation (required if building on Windows/WSL)..."
rustup target add x86_64-unknown-linux-gnu

echo ""
echo "Setup complete. Run 'source \$HOME/.cargo/env' or restart your shell before building."
