package com.webshare.service;

import com.webshare.util.AppLogger;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import java.util.Map;

public class TemplateService {

    private final TemplateEngine engine;

    public TemplateService() {
        this(false); // production mode by default
    }

    /**
     * @param devMode true  → templates reloaded on every request (slow, good for development).
     *                       Pass devMode=true explicitly during development — it is never
     *                       wired automatically by WebShareServer.
     *                false → templates cached after first load (fast, use in production).
     */
    public TemplateService(boolean devMode) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("/templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML); // enum — compile-time checked, not a string
        resolver.setCharacterEncoding("UTF-8");

        // null TTL = no expiry — entries live until evicted (i.e. cache forever)
        resolver.setCacheable(!devMode);
        resolver.setCacheTTLMs(devMode ? 0L : null);

        engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
    }

    /**
     * Renders a template by name with the given variables.
     * Throws TemplateInputException (unchecked) if the template is missing or malformed —
     * callers should handle this rather than relying on the outer catch-all in WebShareServer.
     */
    public String render(String templateName, Map<String, Object> variables) {
        try {
            Context context = new Context();
            if (variables != null) context.setVariables(variables);
            return engine.process(templateName, context);
        } catch (Exception e) {
            // Log here so template errors are visible even if the caller swallows exceptions
            AppLogger.error("Template render failed: " + templateName, e);
            throw e; // re-throw so the caller's error response path is exercised
        }
    }
}
