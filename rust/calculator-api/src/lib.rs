use itara_core::ItaraComponent;

/// The calculator contract.
/// This is all the gateway knows about the calculator — nothing else.
/// No implementation. No transport. No topology.
pub trait CalculatorService: ItaraComponent {
    fn add(&self, a: i64, b: i64) -> i64;
    fn multiply(&self, a: i64, b: i64) -> i64;
}