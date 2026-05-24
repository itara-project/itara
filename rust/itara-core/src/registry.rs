use std::collections::{HashMap, HashSet};
use std::cell::UnsafeCell;
use std::sync::OnceLock;
use crate::component::{ActivatorFn, ItaraComponent, reconstruct_ref};
use std::any::TypeId;

static GLOBAL_REGISTRY: OnceLock<ItaraRegistry> = OnceLock::new();

pub struct WrappingData {
    pub direct_fn:   crate::loader::DirectProxyFactoryFn,
    pub facade_ptr:  *const crate::observability::ObservabilityFacade,
    pub handler_ptr: *const dyn crate::observability::ItaraContextHandler,
}

unsafe impl Send for WrappingData {}
unsafe impl Sync for WrappingData {}

pub struct ItaraRegistry {
    instances:  UnsafeCell<HashMap<String, Box<dyn ItaraComponent>>>,
    activators: HashMap<String, ActivatorFn>,
    /// Direct proxy wrappers — applied immediately after activation.
    /// The raw component is never stored; the proxy replaces it atomically.
    wrappers:   HashMap<String, WrappingData>,
    activating: UnsafeCell<HashSet<String>>,
}

unsafe impl Sync for ItaraRegistry {}
unsafe impl Send for ItaraRegistry {}

impl ItaraRegistry {
    pub fn new() -> Self {
        ItaraRegistry {
            instances:  UnsafeCell::new(HashMap::new()),
            activators: HashMap::new(),
            wrappers:   HashMap::new(),
            activating: UnsafeCell::new(HashSet::new()),
        }
    }

    pub fn install_global(registry: ItaraRegistry) {
        GLOBAL_REGISTRY
            .set(registry)
            .map_err(|_| ())
            .expect("[Itara] Global registry already installed");
        println!("[Itara] Registry installed — startup complete");
    }

    pub fn global() -> &'static ItaraRegistry {
        GLOBAL_REGISTRY
            .get()
            .expect("[Itara] Global registry not initialised — agent startup incomplete")
    }

    // ── Agent setup API ───────────────────────────────────────────────────

    pub fn register_proxy(&mut self, id: &str, proxy: Box<dyn ItaraComponent>) {
        println!("[Itara] Registered proxy for: {}", id);
        self.instances.get_mut().insert(id.to_string(), proxy);
    }

    /// Register a direct proxy wrapper for a component.
    /// When this component is activated, the raw instance is immediately
    /// wrapped before being stored — the raw impl is never visible.
    pub fn register_wrapper(&mut self, id: &str, data: WrappingData) {
        self.wrappers.insert(id.to_string(), data);
    }

    pub fn register_activator(&mut self, id: &str, activator: ActivatorFn) {
        println!("[Itara] Registered activator for: {}", id);
        self.activators.insert(id.to_string(), activator);
    }

    /// Take ownership of an activated component during pre-install setup.
    /// Used by the agent to wrap components with direct proxies before
    /// install_global is called.
    pub fn take_instance(&mut self, id: &str) -> Option<Box<dyn ItaraComponent>> {
        self.instances.get_mut().remove(id)
    }

    /// Store a component instance during pre-install setup.
    pub fn store_instance(&mut self, id: &str, component: Box<dyn ItaraComponent>) {
        self.instances.get_mut().insert(id.to_string(), component);
    }

    pub fn is_registered(&self, id: &str) -> bool {
        unsafe { &*self.instances.get() }.contains_key(id)
            || self.activators.contains_key(id)
    }

    // ── Agent post-install API ────────────────────────────────────────────

    /// Return the raw fat pointer words for a registered component.
    pub fn get_fat_ptr(&self, id: &str) -> Option<(*const (), *const ())> {
        self.ensure_activated(id);
        let instances = unsafe { &*self.instances.get() };
        let component = instances.get(id)?;
        let raw: *const dyn ItaraComponent = component.as_ref() as *const dyn ItaraComponent;
        let fat: (*const (), *const ()) = unsafe { std::mem::transmute(raw) };
        Some(fat)
    }

    /// Remove and return a component's Box from the registry.
    /// Used by the agent to transfer ownership into a direct proxy wrapper.
    pub fn take_component_global(id: &str) -> Option<Box<dyn ItaraComponent>> {
        let registry = Self::global();
        unsafe { &mut *registry.instances.get() }.remove(id)
    }

    /// Replace a component's registered instance with a direct proxy.
    pub fn replace_with_proxy_global(id: &str, proxy: Box<dyn ItaraComponent>) {
        println!("[Itara] Wrapped '{}' with direct proxy", id);
        let registry = Self::global();
        unsafe { &mut *registry.instances.get() }.insert(id.to_string(), proxy);
    }

    // ── Application / activator API ───────────────────────────────────────

    pub fn get<T: ?Sized + 'static>(&self, id: &str) -> &T {
        self.ensure_activated(id);

        let instances = unsafe { &*self.instances.get() };
        let component = instances.get(id).unwrap();

        let fat = component.cast_to(TypeId::of::<T>()).unwrap_or_else(|| {
            panic!(
                "[Itara] Component '{}' does not implement the requested trait. \
                 Check your wiring config.",
                id
            )
        });

        unsafe { reconstruct_ref::<T>(fat) }
    }

    pub fn ensure_activated(&self, id: &str) {
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
                "[Itara] Topology error: '{}' is not registered. \
                 Check your wiring config.",
                id
            )
        });

        activating.insert(id.to_string());
        println!("[Itara] Activating: {}", id);

        let instance = unsafe { activator(self as *const ItaraRegistry) };

        unsafe { &mut *self.activating.get() }.remove(id);

        // If a direct proxy wrapper is registered, wrap immediately.
        // The raw implementation is never stored — the proxy replaces it
        // before anyone can get a reference to the raw impl.
        let final_instance = if let Some(wrapper) = self.wrappers.get(id) {
            let raw: *mut dyn ItaraComponent = Box::into_raw(instance);
            let fat: (*const (), *const ()) = unsafe {
                std::mem::transmute(raw as *const dyn ItaraComponent)
            };
            let proxy = unsafe {
                (wrapper.direct_fn)(fat.0, fat.1, wrapper.facade_ptr, wrapper.handler_ptr)
            };
            println!("[Itara] Wrapped '{}' with direct proxy", id);
            proxy
        } else {
            instance
        };

        unsafe { &mut *self.instances.get() }.insert(id.to_string(), final_instance);

        println!("[Itara] Activated: {}", id);
    }
}
