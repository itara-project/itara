package demo.transport;

import io.itara.spi.transport.ItaraTransport;
import io.itara.spi.transport.ItaraTransportConfig;
import io.itara.transport.http.HttpTransportConfig;
import io.itara.transport.http.HttpTransportFactory;

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
