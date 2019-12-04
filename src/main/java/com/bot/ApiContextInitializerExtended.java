package com.bot;


import org.telegram.telegrambots.meta.ApiContext;
import org.telegram.telegrambots.meta.generics.BotSession;
import org.telegram.telegrambots.meta.generics.Webhook;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import org.telegram.telegrambots.updatesreceivers.DefaultWebhook;

public class ApiContextInitializerExtended {

    public static void init() {
        ApiContext.register(BotSession.class, BotSessionExtended.class);
        ApiContext.register(Webhook.class, DefaultWebhook.class);
    }
}
