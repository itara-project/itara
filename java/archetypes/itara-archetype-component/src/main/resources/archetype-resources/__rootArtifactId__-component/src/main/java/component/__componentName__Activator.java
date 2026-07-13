package ${package}.component;

import io.itara.api.ItaraActivator;
import io.itara.runtime.ItaraRegistry;
import ${package}.api.${componentName};

public class ${componentName}Activator implements ItaraActivator {

    @Override
    public ${componentName} activate(ItaraRegistry registry) {
        // TODO: fetch any dependencies this component needs from the registry, e.g.:
        // SomeOtherService someOther = registry.get("some-other-component", SomeOtherService.class);
        // return new ${componentName}Impl(someOther);

        return new ${componentName}Impl();
    }
}