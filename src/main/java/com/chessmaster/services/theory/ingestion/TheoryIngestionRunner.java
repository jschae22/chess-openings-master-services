package com.chessmaster.services.theory.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Runs the theory ingestion as a one-shot batch job when {@code ingest.enabled=true},
 * then exits. The web app starts normally without the flag.
 *
 * <pre>./mvnw spring-boot:run -Dspring-boot.run.arguments=--ingest.enabled=true \
 *     -Dspring-boot.run.profiles=local</pre>
 */
@Component
@ConditionalOnProperty(name = "ingest.enabled", havingValue = "true")
public class TheoryIngestionRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TheoryIngestionRunner.class);

    private final TheoryIngestionService ingestionService;
    private final ConfigurableApplicationContext context;

    public TheoryIngestionRunner(TheoryIngestionService ingestionService,
                                 ConfigurableApplicationContext context) {
        this.ingestionService = ingestionService;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Theory ingestion starting");
        int exitCode;
        try {
            IngestionReport report = ingestionService.run();
            log.info("Theory ingestion complete\n{}", report.summary());
            exitCode = 0;
        } catch (RuntimeException e) {
            log.error("Theory ingestion failed", e);
            exitCode = 1;
        }
        int code = exitCode;
        System.exit(SpringApplication.exit(context, () -> code));
    }
}
