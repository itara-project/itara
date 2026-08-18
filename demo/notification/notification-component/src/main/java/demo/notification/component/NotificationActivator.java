package demo.notification.component;

import demo.notification.api.NotificationService;
import io.itara.api.ItaraActivator;

import java.util.logging.Logger;

public class NotificationActivator implements ItaraActivator {

    private static final Logger log = Logger.getLogger(NotificationActivator.class.getName());

    @Override
    public NotificationService activate() {
        log.info("[NotificationActivator] Creating NotificationServiceImpl");
        return new NotificationServiceImpl();
    }
}
