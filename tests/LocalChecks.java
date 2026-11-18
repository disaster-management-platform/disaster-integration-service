import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import platform.integration.domain.OperationIdentity;
import platform.integration.domain.ExternalReportSource;
import platform.integration.integration.http.BoundedTransport;

public final class LocalChecks {
    private static int checks;
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++; System.out.println("PASS " + name);
    }
    private static void failure(int expected, Runnable call, String name) {
        try { call.run(); throw new AssertionError(name + ": expected failure"); }
        catch (BoundedTransport.Failure result) { check(result.status() == expected, name); }
    }
    private static void invalid(Runnable call, String name) {
        try { call.run(); throw new AssertionError(name + ": expected rejection"); }
        catch (IllegalArgumentException result) { check(true, name); }
    }
    public static void main(String[] args) throws Exception {
        check(OperationIdentity.key("org", "event", "17").equals(OperationIdentity.key("org", "event", "17")), "stable operation identity");
        check(!OperationIdentity.key("a/b", "c").equals(OperationIdentity.key("a", "b/c")), "unambiguous scope tuple");
        check(!OperationIdentity.key("org-a", "17").equals(OperationIdentity.key("org-b", "17")), "separate organization keys");
        check(!ExternalReportSource.identify("gis", "issuer", "org", "actor").equals(ExternalReportSource.identify("hospital", "issuer", "org", "actor")), "source category separation");
        check(!ExternalReportSource.identify("gis", "issuer", "org", "actor").equals(ExternalReportSource.identify("gis", "issuer", "other", "actor")), "source organization separation");
        check(!ExternalReportSource.identify("gis", "issuer", "org", "actor").equals(ExternalReportSource.identify("gis", "issuer", "org", "other")), "source actor separation");
        check(!ExternalReportSource.identify("gis", "issuer", "org", "actor").equals(ExternalReportSource.identify("gis", "otherIssuer", "org", "actor")), "source issuer separation");
        invalid(() -> ExternalReportSource.identify("unknown", "issuer", "org", "actor"), "unknown category rejected");
        invalid(() -> BoundedTransport.base("http://example.org", false), "non-TLS remote endpoint rejected");
        invalid(() -> BoundedTransport.base("https://user:pass@example.org", false), "credentials in URL rejected");
        invalid(() -> BoundedTransport.base("https://example.org?x=1", false), "base query rejected");
        check(BoundedTransport.base("http://127.0.0.1:1234/", false).equals("http://127.0.0.1:1234"), "loopback development URL normalized");
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/ok", exchange -> {
            byte[] bytes="{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out=exchange.getResponseBody()) {out.write(bytes);}
        });
        server.createContext("/post", exchange -> {
            boolean authorized="Bearer synthetic".equals(exchange.getRequestHeaders().getFirst("Authorization"));
            boolean content="application/json".equals(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] bytes=exchange.getRequestBody().readAllBytes();
            boolean valid=authorized && content && "POST".equals(exchange.getRequestMethod()) && "{\"x\":1}".equals(new String(bytes, StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(valid ? 201 : 400, bytes.length);
            try (var out=exchange.getResponseBody()) {out.write(bytes);}
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/ok"); exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/large", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try(var out=exchange.getResponseBody()){out.write(new byte[1025]);} catch(IOException ignored){}
        });
        server.createContext("/slow", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try(var out=exchange.getResponseBody()){out.write(65);out.flush();Thread.sleep(1500);out.write(66);}catch(IOException|InterruptedException ignored){}
        });
        server.createContext("/hold", exchange -> {
            started.countDown();
            try{release.await(5, TimeUnit.SECONDS);}catch(InterruptedException ignored){}
            exchange.sendResponseHeaders(204,-1);exchange.close();
        });
        server.start(); String base="http://127.0.0.1:"+server.getAddress().getPort();
        try (var transport=new BoundedTransport(1,1024); var other=new BoundedTransport(1,1024)) {
            var response=transport.request(base,"/ok","Bearer synthetic",null,3000);
            check(response.status()==200 && response.body().equals("{\"ok\":true}"),"HTTP GET real loopback");
            check(transport.request(base,"/post","Bearer synthetic","{\"x\":1}",3000).status()==201,"HTTP POST preserves authorization and content");
            check(transport.request(base,"/redirect","Bearer synthetic",null,3000).status()==302,"redirect not followed");
            failure(502,()->transport.request(base,"/large","Bearer synthetic",null,3000),"streaming response byte limit");
            failure(504,()->transport.request(base,"/slow","Bearer synthetic",null,250),"deadline includes slow response body");
            check(transport.request(base,"/ok","Bearer synthetic",null,3000).status()==200,"slot released after timeout");
            var active=executor.submit(()->transport.request(base,"/hold","Bearer synthetic",null,4000));
            check(started.await(2,TimeUnit.SECONDS),"concurrent request entered dependency");
            failure(503,()->transport.request(base,"/ok","Bearer synthetic",null,3000),"bulkhead rejects saturation");
            check(other.request(base,"/ok","Bearer synthetic",null,3000).status()==200,"dependency bulkheads independent");
            release.countDown();check(active.get(4,TimeUnit.SECONDS).status()==204,"active request completes");
            check(transport.request(base,"/ok","Bearer synthetic",null,3000).status()==200,"recovery after saturation");
        } finally {release.countDown(); server.stop(0); executor.shutdownNow();}
        System.out.println("Passed "+checks+" checks (JDK only; no WildFly/Eden/database).");
    }
}
