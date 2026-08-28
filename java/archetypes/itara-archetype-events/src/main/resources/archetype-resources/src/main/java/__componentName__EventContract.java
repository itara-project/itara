package ${package};

import dev.itara.api.EventContractInterface;

/**
 * Example event contract. An events artifact may contain many contracts —
 * this is a single example showing the expected structure.
 *
 * TODO: rename this class and the id below to describe an actual event this
 * artifact carries, then add further contracts in this package following
 * the same pattern for every other event this artifact needs to declare.
 */
@EventContractInterface(id = "${componentName}-event")
public interface ${componentName}EventContract {

    void on${componentName}Event(String id);
}