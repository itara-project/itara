use itara_core::ItaraComponent;

/// The gateway contract.
pub trait GatewayService: ItaraComponent {
    fn calculate(&self, op: &str, a: i64, b: i64) -> i64;
}