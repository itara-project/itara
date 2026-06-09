#!/bin/bash
# Run this in WSL before docker compose up.
# Builds the payment binary natively on Linux — no cross-compilation needed.
#
# Usage (from WSL, in the demo/ directory):
#   ./build-payment.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "[build-payment] Building payment-server..."
cd "$SCRIPT_DIR/payment"
cargo build --release

echo "[build-payment] Done: payment/target/release/payment-server"
