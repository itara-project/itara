package demo.fulfilment.component;

import demo.fulfilment.api.FulfilmentService;
import io.itara.api.ItaraActivator;
import io.itara.runtime.ItaraRegistry;

import java.util.logging.Logger;

public class FulfilmentActivator implements ItaraActivator {

    private static final Logger log = Logger.getLogger(FulfilmentActivator.class.getName());

    @Override
    public FulfilmentService activate(ItaraRegistry registry) {
        log.info("[FulfilmentActivator] Creating FulfilmentServiceImpl");
        return new FulfilmentServiceImpl();
    }
}
