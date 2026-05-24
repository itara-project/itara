//! itara-core — the foundation of the Itara runtime.
//!
//! Defines all SPI traits, the component model, the registry, and the
//! observability types. Everything else in the Itara ecosystem depends on
//! this crate. It depends on nothing Itara-specific.
 
mod component;
mod transport;
mod registry;
mod loader;
mod observability;
 
pub use component::*;
pub use transport::*;
pub use registry::*;
pub use loader::*;
pub use observability::*;