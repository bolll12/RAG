package com.pingan.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class RagApplication {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && (args[0].equals("ingest") || args[0].equals("eval") || args[0].equals("reindex"))) {
            System.exit(RagCli.run(args));
        }
        SpringApplication.run(RagApplication.class, args);
    }
    @Bean RagSettings ragSettings() { return RagSettings.load(); }
    @Bean RagStore ragStore(RagSettings settings) { return new RagStore(settings); }
    @Bean ModelGateway modelGateway(RagSettings settings) { return new CompatibleModelClient(settings); }
    @Bean RagService ragService(RagSettings settings, RagStore store, ModelGateway models) {
        return new RagService(settings, store, models);
    }
}
