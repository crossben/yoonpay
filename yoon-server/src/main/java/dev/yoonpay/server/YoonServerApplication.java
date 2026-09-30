package dev.yoonpay.server;

import dev.yoonpay.server.auth.ApiKeyService;
import dev.yoonpay.server.auth.AppsCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class YoonServerApplication {

    public static void main(String[] args) {
        if (AppsCommand.matches(args)) {
            SpringApplication app = new SpringApplication(YoonServerApplication.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            app.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
            // An operator tool: print the answer, not the framework's startup log.
            app.setDefaultProperties(java.util.Map.of(
                    "logging.level.root", "WARN",
                    "yoon.scheduling.enabled", "false"));
            try (ConfigurableApplicationContext ctx = app.run()) {
                System.exit(AppsCommand.run(args, ctx.getBean(ApiKeyService.class), System.out));
            }
        }
        SpringApplication.run(YoonServerApplication.class, args);
    }
}
