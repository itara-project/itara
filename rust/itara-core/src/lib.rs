use std::any::{Any, TypeId};
use std::collections::{HashMap, HashSet};
use std::cell::UnsafeCell;
use std::sync::OnceLock;

// ── Marker trait ──────────────────────────────────────────────────────────────
//
// Every component implementation must impl this.
// cast_to() is how the registry resolves a type-erased Box<dyn ItaraComponent>
// into a reference to a specific API trait without knowing the concrete type.
//
// The pattern in every impl is fixed and mechanical:
//   fn cast_to(&self, trait_id: TypeId) -> Option<*const ()> {
//       if trait_id == TypeId::of::<dyn MyApiTrait>() {
//           Some(self as &dyn MyApiTrait as *const dyn MyApiTrait as *const ())
//       } else {
//           None
//       }
//   }
//
// This will be a #[derive(ItaraComponent)] macro. For the PoC it is written by hand.

pub trait ItaraComponent: Any + Send + Sync {
    fn as_any(&self) -> &dyn Any;

    /// Cast self to the trait object identified by trait_id.
    /// Returns the fat pointer erased to *const () — vtable is preserved because
    /// *const dyn Trait is two words (data + vtable), and transmute_copy in
    /// reconstruct_ref() recovers both. Returns None if this component does
    /// not implement the requested trait.
    fn cast_to(&self, trait_id: TypeId) -> Option<(*const (), *const ())>;
}

// ── Fat pointer reconstruction ────────────────────────────────────────────────
//
// cast_to() stores `self as &dyn MyTrait as *const dyn MyTrait as *const ()`.
// This preserves both words of the fat pointer (data + vtable).
// reconstruct_ref() transmutes it back to recover the original fat pointer.
//
// Safe as long as the component lives for the process lifetime (enforced by
// the registry holding ownership).

/// Reconstruct a &'static dyn T from a pointer produced by cast_to().
///
/// # Safety
/// - ptr must have been produced by cast_to() on a component stored in the registry.
/// - T must be the exact trait type used to produce the pointer.
/// - Only called from ItaraRegistry::get(), which guarantees process lifetime.
unsafe fn reconstruct_ref<T: ?Sized + 'static>(fat: (*const (), *const ())) -> &'static T {
    // Reconstruct the fat pointer from its two words: (data, vtable)
    let fat_ptr: (*const (), *const ()) = fat;
    let result: &T = std::mem::transmute_copy(&fat_ptr);
    result
}

// ── Registry ──────────────────────────────────────────────────────────────────

/// The activator function signature exported by every component .so.
/// No parameters — activators reach into the global registry for dependencies.
pub type ActivatorFn = unsafe extern "C" fn(registry: *const ItaraRegistry) -> Box<dyn ItaraComponent>;


static GLOBAL_REGISTRY: OnceLock<ItaraRegistry> = OnceLock::new();

pub struct ItaraRegistry {
    /// Fully initialised instances — remote proxies and activated locals.
    /// UnsafeCell because activators trigger each other during startup
    /// (single-threaded). Frozen after install_global() — read-only at call time.
    instances: UnsafeCell<HashMap<String, Box<dyn ItaraComponent>>>,

    /// Activator function pointers for local components.
    activators: HashMap<String, ActivatorFn>,

    /// Circular dependency detection during startup.
    activating: UnsafeCell<HashSet<String>>,
}

// SAFETY: startup is single-threaded. After install_global() the registry
// is read-only — no mutation ever occurs at call time.
unsafe impl Sync for ItaraRegistry {}
unsafe impl Send for ItaraRegistry {}

impl ItaraRegistry {
    pub fn new() -> Self {
        ItaraRegistry {
            instances: UnsafeCell::new(HashMap::new()),
            activators: HashMap::new(),
            activating: UnsafeCell::new(HashSet::new()),
        }
    }

    /// Install as process global. Called once by the agent after all
    /// registrations are complete. Panics if called twice.
    pub fn install_global(registry: ItaraRegistry) {
        GLOBAL_REGISTRY
            .set(registry)
            .map_err(|_| ())
            .expect("[Itara] Global registry already installed");
        println!("[Itara] Registry installed — startup complete");
    }

    /// Access the process-global registry.
    /// Panics if the agent has not called install_global() yet.
    pub fn global() -> &'static ItaraRegistry {
        GLOBAL_REGISTRY
            .get()
            .expect("[Itara] Global registry not initialised — agent startup incomplete")
    }

    // ── Agent setup API ───────────────────────────────────────────────────

    /// Pre-register a remote proxy constructed by the agent.
    pub fn preregister(&mut self, id: &str, proxy: Box<dyn ItaraComponent>) {
        println!("[Itara] Pre-registered remote proxy for: {}", id);
        self.instances.get_mut().insert(id.to_string(), proxy);
    }

    /// Register a local component's activator. Activation is lazy —
    /// the activator runs on the first get() for this id.
    pub fn register_activator(&mut self, id: &str, activator: ActivatorFn) {
        println!("[Itara] Registered activator for: {}", id);
        self.activators.insert(id.to_string(), activator);
    }

    // ── Application / activator API ───────────────────────────────────────

    /// Retrieve a component by id as a reference to the requested trait T.
    /// The caller names only the API trait — never the concrete type.
    ///
    /// Panics at startup — not at call time — if:
    ///   - the id is not registered (topology config error)
    ///   - the component does not implement T (contract mismatch)
    ///   - a circular dependency is detected during activation
    pub fn get<T: ?Sized + 'static>(&self, id: &str) -> &T {
        self.ensure_activated(id);

        let instances = unsafe { &*self.instances.get() };
        
        let component = instances.get(id).unwrap();

        let trait_id = TypeId::of::<T>();
        let ptr = component.cast_to(trait_id).unwrap_or_else(|| {
            panic!(
                "[Itara] Component '{}' does not implement the requested trait. \
                 Check your wiring config or cast_to() implementation.",
                id
            )
        });

        // SAFETY: see reconstruct_ref() contract above.
        unsafe { reconstruct_ref::<T>(ptr) }
    }

    fn ensure_activated(&self, id: &str) {
        if unsafe { &*self.instances.get() }.contains_key(id) {
            return;
        }

        let activating = unsafe { &mut *self.activating.get() };
        if activating.contains(id) {
            panic!(
                "[Itara] Circular dependency detected while activating '{}'. \
                 Check your wiring config.",
                id
            );
        }

        let activator = self.activators.get(id).copied().unwrap_or_else(|| {
            panic!(
                "[Itara] Topology error: '{}' is not registered in this process. \
                 Check your wiring config.",
                id
            )
        });

        activating.insert(id.to_string());
        println!("[Itara] Activating: {}", id);

        let instance = unsafe { activator(self as *const ItaraRegistry) };

        unsafe { &mut *self.activating.get() }.remove(id);
        unsafe { &mut *self.instances.get() }.insert(id.to_string(), instance);

        println!("[Itara] Activated: {}", id);
    }
}

// ── .so loader ────────────────────────────────────────────────────────────────

/// Load a component .so and register its activator.
/// This and reconstruct_ref() are the only unsafe surfaces in the framework.
pub fn load_and_register(
    registry: &mut ItaraRegistry,
    component_id: &str,
    lib_path: &str,
) -> Result<(), String> {
    unsafe {
        let lib = libloading::Library::new(lib_path)
            .map_err(|e| format!("[Itara] Failed to load '{}': {}", lib_path, e))?;

        let activator: libloading::Symbol<ActivatorFn> = lib
            .get(b"itara_activator\0")
            .map_err(|e| format!("[Itara] No itara_activator symbol in '{}': {}", lib_path, e))?;

        let activator_fn: ActivatorFn = *activator;

        // Leak the library — components live for the process lifetime.
        // The .so must not be unloaded while any component reference is live.
        std::mem::forget(lib);

        registry.register_activator(component_id, activator_fn);
        Ok(())
    }
}
