package demo.fulfilment.component;

import demo.fulfilment.api.FulfilmentService;
import io.itara.api.ItaraActivator;

import java.util.logging.Logger;

public class FulfilmentActivator implements ItaraActivator {

    private static final Logger log = Logger.getLogger(FulfilmentActivator.class.getName());

    @Override
    public FulfilmentService activate() {
        log.info("[FulfilmentActivator] Creating FulfilmentServiceImpl");
        return new FulfilmentServiceImpl();
    }
}
