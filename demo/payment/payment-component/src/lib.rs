use std::any::{Any, TypeId};
use payment_api::PaymentService;
use itara_core::{ItaraComponent, ItaraRegistry};
 
pub struct PaymentServiceImpl;
 
impl PaymentService for PaymentServiceImpl {
    fn process_payment(&self, order_id: String, amount_cents: i64, currency: String) -> bool {
        println!(
            "[payment] Processing payment — order_id={}, amount={} {}, status=approved",
            order_id,
            amount_cents,
            currency,
        );
        true
    }
}
 
impl ItaraComponent for PaymentServiceImpl {
    fn as_any(&self) -> &dyn Any {
        self
    }
 
    fn cast_to(&self, trait_id: TypeId) -> Option<(*const (), *const ())> {
        if trait_id == TypeId::of::<dyn PaymentService>() {
            let fat = self as &dyn PaymentService as *const dyn PaymentService;
            Some(unsafe { std::mem::transmute(fat) })
        } else {
            None
        }
    }
}
 
#[unsafe(no_mangle)]
pub extern "C" fn itara_activator(_registry: *const ItaraRegistry) -> Box<dyn ItaraComponent> {
    println!("[payment] activator called");
    Box::new(PaymentServiceImpl)
}
