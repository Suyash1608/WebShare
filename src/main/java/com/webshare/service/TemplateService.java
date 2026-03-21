package com.webshare.service;

import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.Map;

public class TemplateService {

    private final TemplateEngine engine;

    public TemplateService() {
        this(false); // production mode by default
    }

    /**
     * @param devMode true  → templates reloaded on every request (slow, good for development)
     *                false → templates cached after first load (fast, use in production)
     */
    public TemplateService(boolean devMode) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("/templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding("UTF-8");

        // Cache templates in production — never reload in production
        resolver.setCacheable(!devMode);
        resolver.setCacheTTLMs(devMode ? 0L : null); // null = cache forever

        engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
    }

    public String render(String templateName, Map<String, Object> variables) {
        Context context = new Context();
        if (variables != null) context.setVariables(variables);
        return engine.process(templateName, context);
    }
}