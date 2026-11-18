package platform.integration.integration.eden;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.ws.rs.WebApplicationException;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@ApplicationScoped
public class EdenHttpClient {
    private static final int MAX_RESPONSE_BYTES=4*1024*1024;
    private final Semaphore capacity=new Semaphore(8);
    private HttpClient client;
    private String authorizationHeader;
    private String baseUrl;

    @PostConstruct
    void start() {
        URI base=URI.create(setting("EDEN_BASE_URL"));
        boolean local=Set.of("localhost","127.0.0.1","::1","[::1]").contains(base.getHost());
        if(base.getHost()==null||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null||!("https".equals(base.getScheme())||"http".equals(base.getScheme())&&(local||"true".equals(System.getenv("EDEN_ALLOW_DEVELOPMENT_HTTP")))))throw new IllegalArgumentException("EDEN_BASE_URL requires HTTPS outside authorized development");
        baseUrl=base.toString().replaceAll("/+$","");
        authorizationHeader="Basic "+Base64.getEncoder().encodeToString((setting("EDEN_TECH_USER")+":"+setting("EDEN_TECH_PASSWORD")).getBytes(StandardCharsets.UTF_8));
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @PreDestroy void stop(){if(client!=null)client.close();}
    public String get(String path) {
        if(!capacity.tryAcquire())throw new WebApplicationException(503);
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try {
            HttpRequest request=HttpRequest.newBuilder(URI.create(baseUrl+path)).timeout(Duration.ofSeconds(10)).header("Authorization",authorizationHeader).header("Accept","application/json").GET().build();
            pending=client.sendAsync(request,info->new BoundedBody());
            var response=pending.get(10,TimeUnit.SECONDS);
            int status=response.statusCode();
            if(status==401||status==403||status>=500)throw new WebApplicationException(503);
            if(status<200||status>=300)throw new WebApplicationException(502);
            return new String(response.body(),StandardCharsets.UTF_8);
        }catch(TimeoutException failure){throw new WebApplicationException(504);}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw new WebApplicationException(503);}
        catch(ExecutionException failure){
            Throwable cause=failure.getCause();
            if(cause instanceof HttpTimeoutException)throw new WebApplicationException(504);
            if(cause instanceof IllegalArgumentException)throw new WebApplicationException(502);
            throw new WebApplicationException(503);
        }finally{if(pending!=null&&!pending.isDone())pending.cancel(true);capacity.release();}
    }
    public boolean probe() {
        try(var reader=Json.createReader(new StringReader(get("/org/organisation.json?start=0&limit=1")))) {
            var array=reader.readArray();
            if(array.size()>1)return false;
            return array.isEmpty()||OrganizationAdapter.validIdentity(array.getJsonObject(0));
        }catch(RuntimeException failure){return false;}
    }
    private static class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        public void onNext(List<ByteBuffer> buffers){
            for(var buffer:buffers){
                if(buffer.remaining()>MAX_RESPONSE_BYTES-bytes.size()){subscription.cancel();result.completeExceptionally(new IllegalArgumentException("Response limit"));return;}
                byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error){result.completeExceptionally(error);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
    private String setting(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalStateException("Required environment setting: "+key);return value;}
}
