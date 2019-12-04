package com.sincro;

import com.bot.ApiContextInitializerExtended;
import com.bot.Bot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.context.annotation.PropertySource;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;


import javax.servlet.ServletContext;
import javax.servlet.ServletException;

/**
 * The Class IntegrationApplication.
 */
@SpringBootApplication
@PropertySource(value = "file:${catalina.home}/conf/application.properties", ignoreResourceNotFound=true)
public class App extends SpringBootServletInitializer {

    /** The Constant LOGGER. */
    private static final Logger LOGGER = LoggerFactory.getLogger(App.class);


    public void onStartup(ServletContext servletContext) throws ServletException {
        super.onStartup(servletContext);
        ApiContextInitializerExtended.init();
        TelegramBotsApi telegramBotsApi = new TelegramBotsApi();
        try {
            telegramBotsApi.registerBot(new Bot());
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }



}