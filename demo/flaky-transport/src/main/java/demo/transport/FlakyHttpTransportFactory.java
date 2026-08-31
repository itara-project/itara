package demo.transport;

import dev.itara.spi.transport.ItaraTransport;
import dev.itara.spi.transport.ItaraTransportConfig;
import dev.itara.transport.http.HttpTransportConfig;
import dev.itara.transport.http.HttpTransportFactory;

public class FlakyHttpTransportFactory extends HttpTransportFactory {

    @Override
    public String id() {
        return "flaky-http";
    }

    @Override
    public ItaraTransport create(ItaraTransportConfig config) throws Exception {
        return new FlakyHttpTransport((HttpTransportConfig) config);
    }
}
