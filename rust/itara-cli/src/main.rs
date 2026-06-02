use clap::{Parser, Subcommand};
use std::process;
 
mod commands;
mod output;
 
/// itara — tooling for Itara wiring configs.
///
/// Operates on the master wiring config: a single file that describes
/// the complete topology of the system.
#[derive(Parser)]
#[command(name = "itara", version, about, long_about = None)]
struct Cli {
    #[command(subcommand)]
    command: Command,
}
 
#[derive(Subcommand)]
enum Command {
    /// Print a human-readable summary of the topology.
    Inspect(commands::inspect::Args),
    /// Validate the logical correctness of the wiring config.
    Verify(commands::verify::Args),
}
 
fn main() {
    let cli = Cli::parse();
 
    let exit_code = match cli.command {
        Command::Inspect(args) => commands::inspect::run(args),
        Command::Verify(args) => commands::verify::run(args),
    };
 
    process::exit(exit_code);
}
