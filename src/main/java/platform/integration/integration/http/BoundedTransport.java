package platform.integration.integration.http;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** A bounded, non-redirecting transport. Each dependency owns its own bulkhead. */
public final class BoundedTransport implements AutoCloseable {
    public record Result(int status, String body) { }
    public static final class Failure extends RuntimeException {
        private final int status;
        public Failure(int status) { this.status = status; }
        public int status() { return status; }
    }
    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Semaphore slots;
    private final int maxBytes;

    public BoundedTransport(int concurrency, int maxBytes) {
        if (concurrency < 1 || maxBytes < 1) throw new IllegalArgumentException();
        this.slots = new Semaphore(concurrency);
        this.maxBytes = maxBytes;
    }

    public static String base(String value, boolean developmentHttp) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing dependency URL");
        URI uri = URI.create(value);
        boolean local = uri.getHost() != null && Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null ||
            !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) && (local || developmentHttp)))
            throw new IllegalArgumentException("HTTPS required outside authorized development");
        return value.replaceAll("/+$", "");
    }

    public Result request(String base, String path, String authorization, String body, long timeoutMillis) {
        if (timeoutMillis <= 0) throw new Failure(504);
        if (!path.startsWith("/") || path.startsWith("//") || path.indexOf('#') >= 0) throw new IllegalArgumentException("Invalid dependency path");
        if (!slots.tryAcquire()) throw new Failure(503);
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofMillis(timeoutMillis))
                .header("Accept", "application/json").header("Authorization", authorization);
            if (body == null) builder.GET();
            else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            pending = client.sendAsync(builder.build(), info -> new BoundedBody(maxBytes));
            var response = pending.get(timeoutMillis, TimeUnit.MILLISECONDS);
            return new Result(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        } catch (TimeoutException e) { throw new Failure(504); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Failure(503); }
        catch (ExecutionException e) {
            for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof HttpTimeoutException) throw new Failure(504);
                if (cause instanceof ResponseLimit) throw new Failure(502);
            }
            throw new Failure(503);
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            slots.release();
        }
    }

    @Override public void close() { client.close(); }
    private static final class ResponseLimit extends RuntimeException { }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int maxBytes;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        BoundedBody(int maxBytes) { this.maxBytes = maxBytes; }
        public CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > maxBytes - bytes.size()) {
                    subscription.cancel(); body.completeExceptionally(new ResponseLimit()); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { body.completeExceptionally(error); }
        public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
