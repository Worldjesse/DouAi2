package com.douai.module.config;

import android.net.Uri;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Editable query parameters backed by one complete GET URL. */
public final class ThirdPartyRequestTemplate {
    public final String baseUrl;
    public final List<Parameter> parameters;
    private final List<String> allowedTokens;

    private ThirdPartyRequestTemplate(String baseUrl, List<Parameter> parameters, List<String> allowedTokens) {
        this.baseUrl = baseUrl;
        this.parameters = Collections.unmodifiableList(parameters);
        this.allowedTokens = Collections.unmodifiableList(new ArrayList<>(allowedTokens));
    }

    public static ThirdPartyRequestTemplate parse(String fullTemplate) {
        return parse(fullTemplate, "{url}");
    }

    public static ThirdPartyRequestTemplate parse(String fullTemplate, String... allowedTokens) {
        List<String> tokens = new ArrayList<>();
        if (allowedTokens == null) throw new IllegalArgumentException("GET placeholders are missing");
        for (String token : allowedTokens) {
            if (token == null || !token.matches("\\{[A-Za-z_][A-Za-z_0-9]*\\}")) {
                throw new IllegalArgumentException("GET placeholder is invalid");
            }
            if (!tokens.contains(token)) tokens.add(token);
        }
        Uri uri = validatedUri(fullTemplate, tokens);
        List<Parameter> parameters = new ArrayList<>();
        String query = uri.getEncodedQuery();
        if (query != null && !query.isEmpty()) {
            // Uri's named-query lookup misses noncanonical encoded keys and groups repeated names.
            for (String part : query.split("&", -1)) {
                if (part.isEmpty()) continue;
                int equals = part.indexOf('=');
                String name = equals < 0 ? part : part.substring(0, equals);
                String value = equals < 0 ? "" : part.substring(equals + 1);
                parameters.add(new Parameter(Uri.decode(name.replace("+", "%20")),
                        Uri.decode(value.replace("+", "%20"))));
            }
        }
        return new ThirdPartyRequestTemplate(uri.buildUpon().clearQuery().build().toString(), parameters, tokens);
    }

    public String withParameters(List<Parameter> next) {
        if (next == null) throw new IllegalArgumentException("GET parameters are missing");
        Uri.Builder builder = Uri.parse(baseUrl).buildUpon().clearQuery();
        for (Parameter parameter : next) {
            if (parameter == null || parameter.name == null || parameter.name.trim().isEmpty()
                    || parameter.value == null) {
                throw new IllegalArgumentException("GET parameters need a name and value");
            }
            String literal = parameter.value;
            for (String token : allowedTokens) literal = literal.replace(token, "");
            if (java.util.regex.Pattern.compile("\\{[A-Za-z_][A-Za-z_0-9]*\\}").matcher(literal).find()) {
                throw new IllegalArgumentException("GET parameter contains an unknown placeholder");
            }
            builder.appendQueryParameter(parameter.name, parameter.value);
        }
        String template = builder.build().toString();
        for (String token : allowedTokens) template = template.replace(Uri.encode(token), token);
        validatedUri(template, allowedTokens);
        return template;
    }

    private static Uri validatedUri(String template, List<String> tokens) {
        if (template == null || template.trim().isEmpty()) {
            throw new IllegalArgumentException("Third-party GET template is empty");
        }
        String value = template.trim();
        try {
            String concrete = value;
            for (String token : tokens) concrete = concrete.replace(token, "douai-placeholder");
            URI strict = new URI(concrete);
            Uri uri = Uri.parse(value);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || strict.getHost() == null || strict.getHost().isEmpty() || strict.getRawUserInfo() != null
                    || (uri.getAuthority() != null && uri.getAuthority().contains("{"))) {
                throw new IllegalArgumentException();
            }
            if (uri.getFragment() != null) {
                throw new IllegalArgumentException("Third-party GET template cannot contain a fragment");
            }
            return uri;
        } catch (Exception failure) {
            if (failure instanceof IllegalArgumentException
                    && "Third-party GET template cannot contain a fragment".equals(failure.getMessage())) {
                throw (IllegalArgumentException) failure;
            }
            throw new IllegalArgumentException("Third-party GET template must be a valid HTTP(S) URL");
        }
    }

    public static final class Parameter {
        public final String name;
        public final String value;

        public Parameter(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }
}
