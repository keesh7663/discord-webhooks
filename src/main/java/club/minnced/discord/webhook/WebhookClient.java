/*
 *     Copyright 2015-2018 Austin Keener & Michael Ritter & Florian Spieß
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package club.minnced.discord.webhook;

import club.minnced.discord.webhook.exception.HttpException;
import club.minnced.discord.webhook.message.WebhookEmbed;
import club.minnced.discord.webhook.message.WebhookMessage;
import club.minnced.discord.webhook.message.WebhookMessageBuilder;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jetbrains.annotations.Async;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;

public class WebhookClient implements AutoCloseable { //TODO: Message Receive
    public static final String WEBHOOK_URL = "https://discordapp.com/api/v7/webhooks/%s/%s";
    public static final String USER_AGENT = "Webhook(https://github.com/MinnDevelopment/discord-webhooks | 1.0.0)"; //TODO
    public static final Logger LOG = LoggerFactory.getLogger(WebhookClient.class);

    protected final String url;
    protected final long id;
    protected final OkHttpClient client;
    protected final ScheduledExecutorService pool;
    protected final Bucket bucket;
    protected final BlockingQueue<Request> queue;
    protected volatile boolean isQueued;
    protected boolean isShutdown;

    protected WebhookClient(final long id, final String token, final OkHttpClient client, final ScheduledExecutorService pool) {
        this.client = client;
        this.id = id;
        this.url = String.format(WEBHOOK_URL, Long.toUnsignedString(id), token);
        this.pool = pool;
        this.bucket = new Bucket();
        this.queue = new LinkedBlockingQueue<>();
        this.isQueued = false;
    }

    public long getId() {
        return id;
    }

    public String getUrl() {
        return url;
    }

    public CompletableFuture<?> send(WebhookMessage message) {
        Objects.requireNonNull(message, "WebhookMessage");
        return execute(message.getBody());
    }

    public CompletableFuture<?> send(File file) {
        Objects.requireNonNull(file, "File");
        return send(file, file.getName());
    }

    public CompletableFuture<?> send(File file, String fileName) {
        return send(new WebhookMessageBuilder().addFile(fileName, file).build());
    }

    public CompletableFuture<?> send(byte[] data, String fileName) {
        return send(new WebhookMessageBuilder().addFile(fileName, data).build());
    }

    public CompletableFuture<?> send(InputStream data, String fileName) {
        return send(new WebhookMessageBuilder().addFile(fileName, data).build());
    }

    public CompletableFuture<?> send(WebhookEmbed[] embeds) {
        return send(WebhookMessage.embeds(Arrays.asList(embeds)));
    }

    public CompletableFuture<?> send(WebhookEmbed first, WebhookEmbed... embeds) {
        return send(WebhookMessage.embeds(first, embeds));
    }

    public CompletableFuture<?> send(Collection<WebhookEmbed> embeds) {
        return send(WebhookMessage.embeds(embeds));
    }

    public CompletableFuture<?> send(String content) {
        Objects.requireNonNull(content, "Content");
        content = content.trim();
        if (content.isEmpty())
            throw new IllegalArgumentException("Cannot send an empty message");
        if (content.length() > 2000)
            throw new IllegalArgumentException("Content may not exceed 2000 characters");
        return execute(newBody(new JSONObject().put("content", content).toString()));
    }

    @Override
    public void close() {
        isShutdown = true;
        pool.shutdown();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void finalize() {
        if (!isShutdown)
            LOG.warn("Detected unclosed WebhookClient! Did you forget to close it?");
    }

    protected void checkShutdown() {
        if (isShutdown)
            throw new RejectedExecutionException("Cannot send to closed client!");
    }

    protected static RequestBody newBody(String object) {
        return RequestBody.create(WebhookMessage.JSON, object);
    }

    protected CompletableFuture<?> execute(RequestBody body) {
        checkShutdown();
        return queueRequest(body);
    }

    protected static HttpException failure(Response response) throws IOException {
        final InputStream stream = getBody(response);
        final String responseBody = stream == null ? "" : new String(IOUtil.readAllBytes(stream));

        return new HttpException("Request returned failure " + response.code() + ": " + responseBody);
    }

    protected CompletableFuture<?> queueRequest(RequestBody body) {
        final boolean wasQueued = isQueued;
        isQueued = true;
        CompletableFuture<?> callback = new CompletableFuture<>();
        Request req = new Request(callback, body);
        enqueuePair(req);
        if (!wasQueued)
            backoffQueue();
        return callback;
    }

    protected okhttp3.Request newRequest(RequestBody body) {
        return new okhttp3.Request.Builder()
                .url(url)
                .method("POST", body)
                .header("accept-encoding", "gzip")
                .header("user-agent", USER_AGENT)
                .build();
    }

    protected void backoffQueue() {
        pool.schedule(this::drainQueue, bucket.retryAfter(), TimeUnit.MILLISECONDS);
    }

    protected void drainQueue() {
        while (!queue.isEmpty()) {
            final Request pair = queue.peek();
            executePair(pair);
        }
        isQueued = false;
    }

    private boolean enqueuePair(@Async.Schedule Request pair) {
        return queue.add(pair);
    }

    private void executePair(@Async.Execute Request req) {
        if (req.future.isCancelled()) {
            queue.poll();
            return;
        }

        final okhttp3.Request request = newRequest(req.body);
        try (Response response = client.newCall(request).execute()) {
            bucket.update(response);
            if (response.code() == Bucket.RATE_LIMIT_CODE) {
                backoffQueue();
                return;
            }
            else if (!response.isSuccessful()) {
                final HttpException exception = failure(response);
                LOG.error("Sending a webhook message failed with non-OK http response", exception);
                queue.poll().future.completeExceptionally(exception);
                return;
            }
            queue.poll().future.complete(null);
            if (bucket.isRateLimit()) {
                backoffQueue();
                return;
            }
        }
        catch (IOException e) {
            LOG.error("There was some error while sending a webhook message", e);
            queue.poll().future.completeExceptionally(e);
        }
    }

    private static InputStream getBody(okhttp3.Response req) throws IOException {
        List<String> encoding = req.headers("content-encoding");
        ResponseBody body = req.body();
        if (!encoding.isEmpty() && body != null) {
            return new GZIPInputStream(body.byteStream());
        }
        return body != null ? body.byteStream() : null;
    }

    protected static final class Bucket {
        public static final int RATE_LIMIT_CODE = 429;
        public long resetTime;
        public int remainingUses;
        public int limit = Integer.MAX_VALUE;

        public synchronized boolean isRateLimit() {
            if (retryAfter() <= 0)
                remainingUses = limit;
            return remainingUses <= 0;
        }

        public synchronized long retryAfter() {
            return resetTime - System.currentTimeMillis();
        }

        private synchronized void handleRatelimit(Response response, long current) throws IOException {
            final String retryAfter = response.header("Retry-After");
            long delay;
            if (retryAfter == null) {
                final JSONObject body = new JSONObject(new JSONTokener(getBody(response)));
                delay = body.getLong("retry_after");
            }
            else {
                delay = Long.parseLong(retryAfter);
            }
            resetTime = current + delay;
        }

        private synchronized void update0(Response response) throws IOException {
            final long current = System.currentTimeMillis();
            final boolean is429 = response.code() == RATE_LIMIT_CODE;
            if (is429) {
                handleRatelimit(response, current);
            }
            else if (!response.isSuccessful()) {
                LOG.debug("Failed to update buckets due to unsuccessful response with code: {} and body: \n{}",
                          response.code(), new IOUtil.Lazy(() -> new String(IOUtil.readAllBytes(getBody(response)))));
                return;
            }
            remainingUses = Integer.parseInt(response.header("X-RateLimit-Remaining"));
            limit = Integer.parseInt(response.header("X-RateLimit-Limit"));
            final String date = response.header("Date");

            if (date != null && !is429) {
                final long reset = Long.parseLong(response.header("X-RateLimit-Reset")); //epoch seconds
                OffsetDateTime tDate = OffsetDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME);
                final long delay = tDate.toInstant().until(Instant.ofEpochSecond(reset), ChronoUnit.MILLIS);
                resetTime = current + delay;
            }
        }

        public void update(Response response) {
            try {
                update0(response);
            }
            catch (Exception ex) {
                LOG.error("Could not read http response", ex);
            }
        }
    }

    private static final class Request {
        private final CompletableFuture<?> future;
        private final RequestBody body;

        public Request(CompletableFuture<?> future, RequestBody body) {
            this.future = future;
            this.body = body;
        }
    }
}
