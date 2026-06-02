/// Terminal output helpers for itara-cli.
///
/// All formatting decisions live here so they are easy to change in one place —
/// whether that means adding colour later, switching to a structured format for
/// machine-readable output, or pulling this into a shared itara-output crate.

// ── Status symbols ─────────────────────────────────────────────────────────────

pub const TICK: &str = "✓";
pub const CROSS: &str = "✗";

// ── Issue severity ─────────────────────────────────────────────────────────────

/// Severity of a single diagnostic item.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Severity {
    Error,
    Warning,
}

/// A single diagnostic produced during verification.
#[derive(Debug, Clone)]
pub struct Issue {
    pub severity: Severity,
    pub message: String,
}

impl Issue {
    pub fn error(message: impl Into<String>) -> Self {
        Self { severity: Severity::Error, message: message.into() }
    }

    pub fn warning(message: impl Into<String>) -> Self {
        Self { severity: Severity::Warning, message: message.into() }
    }

    pub fn is_error(&self) -> bool {
        self.severity == Severity::Error
    }

    pub fn label(&self) -> &str {
        match self.severity {
            Severity::Error   => "ERROR",
            Severity::Warning => "WARN ",
        }
    }
}

// ── Alignment helpers ─────────────────────────────────────────────────────────

/// Print a key–value pair, key left-padded to `width`.
/// Used for the nodes and connections tables in `inspect`.
pub fn kv(key: &str, value: &str, width: usize) {
    println!("  {:<width$} {}", key, value, width = width);
}

/// Print a section header with a trailing blank line.
pub fn section(title: &str) {
    println!("{}:", title);
}

/// Print a blank line.
pub fn blank() {
    println!();
}
