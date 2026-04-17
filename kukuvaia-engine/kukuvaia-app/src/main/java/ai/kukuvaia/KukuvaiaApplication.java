package ai.kukuvaia;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(excludeName = {
        "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration"
})
@ComponentScan(basePackages = {"ai.kukuvaia", "com.embabel"})
public class KukuvaiaApplication {

    public static void main(String[] args) {
        SpringApplication.run(KukuvaiaApplication.class, args);
    }
}
