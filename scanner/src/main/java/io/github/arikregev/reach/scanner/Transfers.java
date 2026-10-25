package io.github.arikregev.reach.scanner;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * GETs and PUTs on the presigned S3 links (architecture.md 3.4). Retries 5xx and I/O errors a few times; anything
 * else is an infrastructure failure of the Job. URLs are never logged: they carry the signature.
 */
final class Transfers {

    private static final int ATTEMPTS = 4;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    byte[] get(String what, URI url) throws TransferException {
        return send(what, HttpRequest.newBuilder(url).timeout(Duration.ofMinutes(2)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    void download(String what, URI url, Path target) throws TransferException {
        send(what, HttpRequest.newBuilder(url).timeout(Duration.ofMinutes(30)).GET().build(),
                HttpResponse.BodyHandlers.ofFile(target));
    }

    void put(String what, URI url, byte[] body, String contentType) throws TransferException {
        send(what, HttpRequest.newBuilder(url).timeout(Duration.ofMinutes(2)).header("Content-Type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.discarding());
    }

    private <T> T send(String what, HttpRequest request, HttpResponse.BodyHandler<T> handler) throws TransferException {
        String last = "";
        for (int i = 1; i <= ATTEMPTS; i++) {
            try {
                HttpResponse<T> r = client.send(request, handler);
                if (r.statusCode() / 100 == 2) {
                    return r.body();
                }
                last = "HTTP " + r.statusCode();
                if (r.statusCode() < 500) {
                    break;
                }
            } catch (IOException e) {
                last = e.getClass().getSimpleName() + ": " + e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TransferException(what + " was interrupted");
            }
            try {
                Thread.sleep(500L << i);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TransferException(what + " was interrupted");
            }
        }
        throw new TransferException(what + " failed: " + last);
    }

    static final class TransferException extends Exception {
        TransferException(String message) {
            super(message);
        }
    }

    static URI readUrl(Path file) throws IOException {
        return URI.create(Files.readString(file).strip());
    }
}
