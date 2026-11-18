package platform.integration.integration.http;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.ws.rs.WebApplicationException;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

@ApplicationScoped
public class ClientCredentials {
    private static final class Entry {
        final Semaphore gate = new Semaphore(1);
        String token;
        long expires;
    }
    private final ConcurrentHashMap<String,Entry> entries = new ConcurrentHashMap<>();
    public String token(String prefix, BoundedTransport transport, long budget) {
        Entry entry=entries.computeIfAbsent(prefix, key -> new Entry());
        if(!entry.gate.tryAcquire())throw new WebApplicationException(503);
        try {
            if(entry.token!=null&&System.nanoTime()<entry.expires)return entry.token;
            URI endpoint=URI.create(required(prefix+"_TOKEN_ENDPOINT"));
            if(!"https".equals(endpoint.getScheme())||endpoint.getHost()==null||endpoint.getUserInfo()!=null||endpoint.getRawQuery()!=null||endpoint.getFragment()!=null)throw new WebApplicationException(503);
            String body="grant_type=client_credentials&client_id="+encode(required(prefix+"_CLIENT_ID"))+"&client_secret="+encode(required(prefix+"_CLIENT_SECRET"));
            long started=System.nanoTime();
            var response=transport.form("https://"+endpoint.getRawAuthority(),endpoint.getRawPath(),body,Math.min(3000,budget));
            if(response.status()!=200||response.body().length()>65536)throw new WebApplicationException(503);
            try(var reader=Json.createReader(new StringReader(response.body()))) {
                var result=reader.readObject();int ttl=result.getInt("expires_in");String value=result.getString("access_token");
                if(ttl<1||ttl>3600||value.isBlank()||!"Bearer".equalsIgnoreCase(result.getString("token_type")))throw new WebApplicationException(503);
                entry.token=value;entry.expires=started+(ttl-Math.min(30,ttl/5))*1_000_000_000L;return value;
            }
        } catch(WebApplicationException failure){throw failure;}
          catch(RuntimeException failure){throw new WebApplicationException(503);}
        finally{entry.gate.release();}
    }
    private static String required(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new WebApplicationException(503);return value;}
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
}
