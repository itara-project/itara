package ${package}.component;

import dev.itara.api.ItaraActivator;
import dev.itara.runtime.ItaraRegistry;
// TODO: confirm this import matches the actual package the API artifact
// declares its interface in — inferred here as ${package}.api.${componentName}.
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