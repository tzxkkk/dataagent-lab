package com.dataagent.lab.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class DatabaseCredentialsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    private static final String PROPERTY_SOURCE_NAME = "dataAgentDatabaseCredentials";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String datasourceUrl = environment.getProperty("spring.datasource.url", "");
        if (!datasourceUrl.startsWith("jdbc:mysql:")) {
            return;
        }

        String configuredUsername = environment.getProperty("spring.datasource.username", "");
        String configuredPassword = environment.getProperty("spring.datasource.password", "");
        if (!configuredUsername.isBlank() && !configuredPassword.isBlank()) {
            return;
        }

        Path credentialsPath = locateCredentialsFile(
                environment.getProperty("dataagent.database.credentials-file", "../mysqlConfigue")
        );
        DatabaseCredentials credentials = readCredentials(credentialsPath);

        Map<String, Object> datasourceProperties = new LinkedHashMap<>();
        if (configuredUsername.isBlank()) {
            datasourceProperties.put("spring.datasource.username", credentials.username());
        }
        if (configuredPassword.isBlank()) {
            datasourceProperties.put("spring.datasource.password", credentials.password());
        }
        environment.getPropertySources().addFirst(
                new MapPropertySource(PROPERTY_SOURCE_NAME, datasourceProperties)
        );
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    private Path locateCredentialsFile(String configuredPath) {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        List<Path> candidates = new ArrayList<>();
        if (configuredPath != null && !configuredPath.isBlank()) {
            Path path = Path.of(configuredPath.trim());
            candidates.add(path.isAbsolute() ? path : workingDirectory.resolve(path));
        }
        candidates.add(workingDirectory.resolve("mysqlConfigue"));
        candidates.add(workingDirectory.resolve("..").resolve("mysqlConfigue"));

        return candidates.stream()
                .map(Path::normalize)
                .distinct()
                .filter(Files::isRegularFile)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "MySQL credentials are required. Provide SPRING_DATASOURCE_USERNAME and "
                                + "SPRING_DATASOURCE_PASSWORD, or create the configured credentials file."
                ));
    }

    private DatabaseCredentials readCredentials(Path path) {
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .toList();
            Map<String, String> namedValues = parseNamedValues(lines);
            String username = firstPresent(namedValues, "username", "user");
            String password = firstPresent(namedValues, "password", "pass");
            if (username == null && password == null && lines.size() == 2) {
                username = lines.get(0);
                password = lines.get(1);
            }
            if (username == null || username.isBlank() || password == null || password.isBlank()) {
                throw new IllegalStateException(
                        "MySQL credentials file must contain username and password lines or named properties."
                );
            }
            return new DatabaseCredentials(username, password);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read the MySQL credentials file.", exception);
        }
    }

    private Map<String, String> parseNamedValues(List<String> lines) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : lines) {
            int separator = line.indexOf('=');
            if (separator <= 0) {
                separator = line.indexOf(':');
            }
            if (separator > 0) {
                values.put(
                        line.substring(0, separator).trim().toLowerCase(Locale.ROOT),
                        line.substring(separator + 1).trim()
                );
            }
        }
        return values;
    }

    private String firstPresent(Map<String, String> values, String... keys) {
        for (String key : keys) {
            String value = values.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private record DatabaseCredentials(String username, String password) {
    }
}
