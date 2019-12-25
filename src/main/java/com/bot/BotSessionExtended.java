package com.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.BufferedHttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.json.JSONException;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.facilities.TelegramHttpClientBuilder;
import org.telegram.telegrambots.meta.api.methods.updates.GetUpdates;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.*;
import org.telegram.telegrambots.meta.logging.BotLogger;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import org.telegram.telegrambots.updatesreceivers.ExponentialBackOff;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidParameterException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

public class BotSessionExtended implements BotSession {
    private static final String LOGTAG = "BOTSESSION";
    private volatile boolean running = false;
    private final ConcurrentLinkedDeque<Update> receivedUpdates = new ConcurrentLinkedDeque();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private BotSessionExtended.ReaderThread readerThread;
    private BotSessionExtended.HandlerThread handlerThread;
    private LongPollingBot callback;
    private String token;
    private int lastReceivedUpdate = 0;
    private DefaultBotOptions options;
    private DefaultBotSession.UpdatesSupplier updatesSupplier;

    @Inject
    public BotSessionExtended() {
    }

    public synchronized void start() {
        if (this.running) {
            throw new IllegalStateException("Session already running");
        } else {
            this.running = true;
            this.lastReceivedUpdate = 0;
            this.readerThread = new BotSessionExtended.ReaderThread(this.updatesSupplier, this);
            this.readerThread.setName(this.callback.getBotUsername() + " Telegram Connection");
            this.readerThread.start();
            this.handlerThread = new BotSessionExtended.HandlerThread();
            this.handlerThread.setName(this.callback.getBotUsername() + " Telegram Executor");
            this.handlerThread.start();
        }
    }

    public synchronized void stop() {
        if (!this.running) {
            throw new IllegalStateException("Session already stopped");
        } else {
            this.running = false;
            if (this.readerThread != null) {
                this.readerThread.interrupt();
            }

            if (this.handlerThread != null) {
                this.handlerThread.interrupt();
            }

            if (this.callback != null) {
                this.callback.onClosing();
            }

        }
    }

    public void setUpdatesSupplier(DefaultBotSession.UpdatesSupplier updatesSupplier) {
        this.updatesSupplier = updatesSupplier;
    }

    public void setOptions(BotOptions options) {
        if (this.options != null) {
            throw new InvalidParameterException("BotOptions has already been set");
        } else {
            this.options = (DefaultBotOptions)options;
        }
    }

    public void setToken(String token) {
        if (this.token != null) {
            throw new InvalidParameterException("Token has already been set");
        } else {
            this.token = token;
        }
    }

    public void setCallback(LongPollingBot callback) {
        if (this.callback != null) {
            throw new InvalidParameterException("Callback has already been set");
        } else {
            this.callback = callback;
        }
    }

    public synchronized boolean isRunning() {
        return this.running;
    }

    private List<Update> getUpdateList() {
        List<Update> updates = new ArrayList();
        Iterator<Update> it = this.receivedUpdates.iterator();

        while(it.hasNext()) {
            updates.add(it.next());
            it.remove();
        }

        return updates;
    }

    private class HandlerThread extends Thread implements UpdatesHandler {
        private HandlerThread() {
        }

        public void run() {
            this.setPriority(1);

            while(BotSessionExtended.this.running) {
                try {
                    List<Update> updates = BotSessionExtended.this.getUpdateList();
                    if (updates.isEmpty()) {
                        synchronized(BotSessionExtended.this.receivedUpdates) {
                            BotSessionExtended.this.receivedUpdates.wait();
                            updates = BotSessionExtended.this.getUpdateList();
                            if (updates.isEmpty()) {
                                continue;
                            }
                        }
                    }

                    BotSessionExtended.this.callback.onUpdatesReceived(updates);
                } catch (InterruptedException var5) {
                    BotLogger.debug("BOTSESSION", var5);
                    this.interrupt();
                } catch (Exception var6) {
                    BotLogger.severe("BOTSESSION", var6);
                }
            }

            BotLogger.debug("BOTSESSION", "Handler thread has being closed");
        }
    }

    public interface UpdatesSupplier {
        List<Update> getUpdates() throws Exception;
    }

    private class ReaderThread extends Thread implements UpdatesReader {
        private final DefaultBotSession.UpdatesSupplier updatesSupplier;
        private final Object lock;
        private CloseableHttpClient httpclient;
        private ExponentialBackOff exponentialBackOff;
        private RequestConfig requestConfig;

        public ReaderThread(DefaultBotSession.UpdatesSupplier updatesSupplier, Object lock) {
            this.updatesSupplier = (DefaultBotSession.UpdatesSupplier) Optional.ofNullable(updatesSupplier).orElse(this::getUpdatesFromServer);
            this.lock = lock;
        }

        public synchronized void start() {
            this.httpclient = TelegramHttpClientBuilder.build(BotSessionExtended.this.options);
            this.requestConfig = BotSessionExtended.this.options.getRequestConfig();
            this.exponentialBackOff = BotSessionExtended.this.options.getExponentialBackOff();
            if (this.exponentialBackOff == null) {
                this.exponentialBackOff = new ExponentialBackOff();
            }

            if (this.requestConfig == null) {
                this.requestConfig = RequestConfig.copy(RequestConfig.custom().build()).setSocketTimeout(75000).setConnectTimeout(75000).setConnectionRequestTimeout(75000).build();
            }

            super.start();
        }

        public void interrupt() {
            if (this.httpclient != null) {
                try {
                    this.httpclient.close();
                } catch (IOException var2) {
                    BotLogger.warn("BOTSESSION", var2);
                }
            }

            super.interrupt();
        }

        public void run() {
            this.setPriority(1);

            while(BotSessionExtended.this.running) {
                synchronized(this.lock) {
                    if (BotSessionExtended.this.running) {
                        try {
                            List<Update> updates = this.updatesSupplier.getUpdates();
                            if (updates.isEmpty()) {
                                this.lock.wait(500L);
                            } else {
                                updates.removeIf((x) -> {
                                    return x.getUpdateId() < BotSessionExtended.this.lastReceivedUpdate;
                                });
                                BotSessionExtended.this.lastReceivedUpdate = (Integer)updates.parallelStream().map(Update::getUpdateId).max(Integer::compareTo).orElse(0);
                                BotSessionExtended.this.receivedUpdates.addAll(updates);
                                synchronized(BotSessionExtended.this.receivedUpdates) {
                                    BotSessionExtended.this.receivedUpdates.notifyAll();
                                }
                            }
                        } catch (InterruptedException var10) {
                            if (!BotSessionExtended.this.running) {
                                BotSessionExtended.this.receivedUpdates.clear();
                            }

                            BotLogger.debug("BOTSESSION", var10);
                            this.interrupt();
                        } catch (Exception var11) {
                            BotLogger.severe("BOTSESSION", var11);

                            try {
                                synchronized(this.lock) {
                                    this.lock.wait(this.exponentialBackOff.nextBackOffMillis());
                                }
                            } catch (InterruptedException var9) {
                                if (!BotSessionExtended.this.running) {
                                    BotSessionExtended.this.receivedUpdates.clear();
                                }

                                BotLogger.debug("BOTSESSION", var9);
                                this.interrupt();
                            }
                        }
                    }
                }
            }

            BotLogger.debug("BOTSESSION", "Reader thread has being closed");
        }

        private List<Update> getUpdatesFromServer() throws IOException {
            GetUpdates request = (new GetUpdates()).setLimit(100).setTimeout(50).setOffset(BotSessionExtended.this.lastReceivedUpdate + 1);
            if (BotSessionExtended.this.options.getAllowedUpdates() != null) {
                request.setAllowedUpdates(BotSessionExtended.this.options.getAllowedUpdates());
            }

            String url = BotSessionExtended.this.options.getBaseUrl() + BotSessionExtended.this.token + "/" + "getupdates";
            HttpPost httpPost = new HttpPost(url);
            httpPost.addHeader("charset", StandardCharsets.UTF_8.name());
            httpPost.setConfig(this.requestConfig);
            httpPost.setEntity(new StringEntity(BotSessionExtended.this.objectMapper.writeValueAsString(request), ContentType.APPLICATION_JSON));

            try {
                CloseableHttpResponse response = this.httpclient.execute(httpPost, BotSessionExtended.this.options.getHttpContext());
                Throwable var5 = null;

                List<Update> var10;
                try {
                    HttpEntity ht = response.getEntity();
                    BufferedHttpEntity buf = new BufferedHttpEntity(ht);
                    String responseContent = EntityUtils.toString(buf, StandardCharsets.UTF_8);
                    if (response.getStatusLine().getStatusCode() >= 500) {
                        BotLogger.warn("BOTSESSION", responseContent);
                        synchronized(this.lock) {
                            this.lock.wait(500L);
                            return Collections.emptyList();
                        }
                    }

                    try {
                        List<Update> updates = request.deserializeResponse(responseContent);
                        this.exponentialBackOff.reset();
                        var10 = updates;
                    } catch (JSONException var26) {
                        BotLogger.severe(responseContent, "BOTSESSION", var26);
                        return Collections.emptyList();
                    }
                } catch (Throwable var27) {
                    var5 = var27;
                    throw var27;
                } finally {
                    if (response != null) {
                        if (var5 != null) {
                            try {
                                response.close();
                            } catch (Throwable var24) {
                                var5.addSuppressed(var24);
                            }
                        } else {
                            response.close();
                        }
                    }

                }

                return var10;
            } catch (InvalidObjectException | SocketException var29) {
                BotLogger.severe("BOTSESSION", var29);
            } catch (TelegramApiRequestException var32){
                if (var32.getErrorCode().equals(409)){
                    this.interrupt();
                    BotSessionExtended.this.stop();
                }else{
                    BotLogger.severe("BOTSESSION", var32);
                }
            } catch (SocketTimeoutException var30) {
                BotLogger.fine("BOTSESSION", var30);
            } catch (InterruptedException var31) {
                BotLogger.fine("BOTSESSION", var31);
                this.interrupt();
            }

            return Collections.emptyList();
        }
    }
}
